package com.ofdeditor.service;

import com.ofdeditor.dto.SignatureVerifyResult;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.ofdrw.gm.cert.PKCGenerate;
import org.ofdrw.gm.ses.v1.SES_ESPictrueInfo;
import org.ofdrw.gm.ses.v1.SES_Header;
import org.ofdrw.gm.ses.v4.CertInfoList;
import org.ofdrw.gm.ses.v4.SESeal;
import org.ofdrw.gm.ses.v4.SES_CertList;
import org.ofdrw.gm.ses.v4.SES_ESPropertyInfo;
import org.ofdrw.gm.ses.v4.SES_SealInfo;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.sign.OFDSigner;
import org.ofdrw.sign.SignMode;
import org.ofdrw.sign.signContainer.SESV4Container;
import org.ofdrw.sign.stamppos.NormalStampPos;
import org.ofdrw.sign.verify.OFDValidator;
import org.ofdrw.sign.verify.SignedDataValidateContainer;
import org.ofdrw.sign.verify.container.GBT35275ValidateContainer;
import org.ofdrw.sign.verify.container.SESV1ValidateContainer;
import org.ofdrw.sign.verify.container.SESV4ValidateContainer;
import org.ofdrw.sign.verify.exceptions.FileIntegrityException;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * OFD 电子签章 / 验签服务（国密 GM/T 0099 SES v4）。
 *
 * - 验签：读取 OFD 内已有签名，做完整性 + 签名值校验，返回结果。
 * - 加盖国密签章：运行时生成 SM2 自签证书 + 构建电子印章（SESeal v4），
 *   用 ofdrw 的 OFDSigner + SESV4Container 非破坏地追加签章（保护原文）。
 *
 * 说明：此处证书为自签测试证书，用于演示/内部用途；如需可信签章，
 * 应改为加载企业 CA 颁发的 SM2 证书（PKCS#12）。
 */
@Slf4j
@Service
public class OfdSignatureService {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // ==================== 验签 ====================

    public SignatureVerifyResult verify(byte[] ofdBytes) throws Exception {
        SignatureVerifyResult result = new SignatureVerifyResult();
        Path tmp = Files.createTempFile("ofd_verify_", ".ofd");
        try {
            Files.write(tmp, ofdBytes);

            int count;
            try (OFDReader reader = new OFDReader(tmp)) {
                if (!reader.hasSignature()) {
                    result.setSigned(false);
                    result.setValid(false);
                    result.setMessage("该文档未包含电子签章或数字签名");
                    return result;
                }
                count = countSignatures(reader);
            }
            result.setSigned(true);
            result.setCount(count);

            // 依次尝试不同验证容器，任一通过即视为有效
            Supplier<SignedDataValidateContainer>[] containers = new Supplier[]{
                    (Supplier<SignedDataValidateContainer>) SESV4ValidateContainer::new,
                    (Supplier<SignedDataValidateContainer>) SESV1ValidateContainer::new,
                    (Supplier<SignedDataValidateContainer>) GBT35275ValidateContainer::new,
            };

            boolean tampered = false;
            Exception lastErr = null;
            for (Supplier<SignedDataValidateContainer> c : containers) {
                try (OFDReader reader = new OFDReader(tmp);
                     OFDValidator validator = new OFDValidator(reader)) {
                    validator.setValidator(c.get());
                    validator.exeValidate();
                    result.setValid(true);
                    result.setMessage("验签通过：文档完整未被篡改，签名值有效（共 " + count + " 个签名）");
                    return result;
                } catch (FileIntegrityException fe) {
                    tampered = true;
                    lastErr = fe;
                } catch (Exception e) {
                    lastErr = e;
                }
            }

            result.setValid(false);
            if (tampered) {
                result.setMessage("验签未通过：文档内容在签名后被修改（完整性校验失败）");
            } else {
                result.setMessage("验签未通过：" +
                        (lastErr != null ? lastErr.getMessage() : "签名值校验失败"));
            }
            return result;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private int countSignatures(OFDReader reader) {
        try {
            var sigs = reader.getDefaultSignatures();
            if (sigs != null && sigs.getSignatures() != null) {
                return sigs.getSignatures().size();
            }
        } catch (Exception e) {
            log.debug("统计签名个数失败: {}", e.getMessage());
        }
        return 1;
    }

    // ==================== 加盖国密签章 ====================

    /**
     * 在指定页指定位置加盖国密电子签章。
     *
     * @param ofdBytes  原始 OFD
     * @param sealPng   印章图片 PNG（可空，空则生成默认红章）
     * @param sealName  印章名称
     * @param page      页码（从 1 开始）
     * @param xMm,yMm   印章左上角位置（mm）
     * @param wMm,hMm   印章尺寸（mm）
     * @return 已签章的 OFD 字节
     */
    public byte[] signGmSeal(byte[] ofdBytes, byte[] sealPng, String sealName,
                             int page, double xMm, double yMm, double wMm, double hMm) throws Exception {
        String name = (sealName == null || sealName.isBlank()) ? "OFD 编辑器测试印章" : sealName.trim();
        byte[] png = (sealPng != null && sealPng.length > 0) ? sealPng : defaultSealImage(name);

        // 1. 生成 SM2 密钥对与自签证书
        KeyPair keyPair = PKCGenerate.GenerateKeyPair();
        X509Certificate cert = selfSignedSm2Cert(keyPair, name);

        // 2. 构建电子印章 SESeal v4
        SESeal seal = buildSeal(keyPair.getPrivate(), cert, name, png, wMm, hMm);

        // 3. 用 OFDSigner 追加签章
        Path src = Files.createTempFile("ofd_sign_src_", ".ofd");
        Path out = Files.createTempFile("ofd_sign_out_", ".ofd");
        try {
            Files.write(src, ofdBytes);
            Files.deleteIfExists(out); // OFDSigner 需要目标不存在
            try (OFDReader reader = new OFDReader(src);
                 OFDSigner signer = new OFDSigner(reader, out)) {
                SESV4Container container = new SESV4Container(keyPair.getPrivate(), seal, cert);
                signer.setSignMode(SignMode.WholeProtected);
                signer.setSignContainer(container);
                signer.addApPos(new NormalStampPos(page, xMm, yMm, wMm, hMm));
                signer.exeSign();
            }
            return Files.readAllBytes(out);
        } finally {
            Files.deleteIfExists(src);
            Files.deleteIfExists(out);
        }
    }

    private X509Certificate selfSignedSm2Cert(KeyPair keyPair, String cn) throws Exception {
        var dn = new org.bouncycastle.asn1.x500.X500Name("CN=" + escapeDn(cn) + ",O=OFD Editor,C=CN");
        Date notBefore = new Date(System.currentTimeMillis() - 24L * 3600 * 1000);
        Date notAfter = new Date(System.currentTimeMillis() + 5L * 365 * 24 * 3600 * 1000);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, serial, notBefore, notAfter, dn, keyPair.getPublic());
        ContentSigner cs = new JcaContentSignerBuilder("SM3withSM2")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(cs);
        return new JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(holder);
    }

    private SESeal buildSeal(PrivateKey prvKey, X509Certificate cert, String name,
                             byte[] png, double wMm, double hMm) throws Exception {
        SES_Header header = new SES_Header(SES_Header.V4, new DERIA5String("OFDRW"));

        CertInfoList certInfoList = new CertInfoList(
                new ASN1OctetString[]{ new DEROctetString(cert.getEncoded()) });
        SES_CertList certList = new SES_CertList(certInfoList);

        ASN1GeneralizedTime now = new ASN1GeneralizedTime(new Date());
        ASN1GeneralizedTime end = new ASN1GeneralizedTime(
                new Date(System.currentTimeMillis() + 5L * 365 * 24 * 3600 * 1000));

        SES_ESPropertyInfo property = new SES_ESPropertyInfo()
                .setType(new ASN1Integer(1))
                .setName(new DERUTF8String(name))
                .setCertListType(SES_ESPropertyInfo.CertListType)
                .setCertList(certList)
                .setCreateDate(now)
                .setValidStart(now)
                .setValidEnd(end);

        SES_ESPictrueInfo picture = new SES_ESPictrueInfo()
                .setType("png")
                .setData(png)
                .setWidth(Math.round(wMm))
                .setHeight(Math.round(hMm));

        SES_SealInfo sealInfo = new SES_SealInfo()
                .setHeader(header)
                .setEsID(UUID.randomUUID().toString().replace("-", ""))
                .setProperty(property)
                .setPicture(picture);

        byte[] toSign = sealInfo.getEncoded(ASN1Encoding.DER);
        Signature sg = Signature.getInstance(
                GMObjectIdentifiers.sm2sign_with_sm3.getId(), BouncyCastleProvider.PROVIDER_NAME);
        sg.initSign(prvKey);
        sg.update(toSign);
        byte[] signedValue = sg.sign();

        return new SESeal()
                .seteSealInfo(sealInfo)
                .setCert(cert)
                .setSignAlgID(GMObjectIdentifiers.sm2sign_with_sm3)
                .setSignedValue(signedValue);
    }

    /** 生成默认红色圆形印章图（含名称文字），用于用户未上传印章图时 */
    private byte[] defaultSealImage(String name) throws Exception {
        int size = 400;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color red = new Color(0xC0, 0x10, 0x10);
        g.setColor(red);
        g.setStroke(new BasicStroke(14f));
        int margin = 20;
        g.draw(new Ellipse2D.Double(margin, margin, size - 2.0 * margin, size - 2.0 * margin));

        // 中心五角星
        drawStar(g, size / 2.0, size / 2.0, 55, red);

        // 环绕/居中名称文字
        String text = name.length() > 12 ? name.substring(0, 12) : name;
        g.setFont(new Font("SimSun", Font.BOLD, 40));
        int tw = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (int) (size / 2.0 - tw / 2.0), (int) (size * 0.78));
        g.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private void drawStar(Graphics2D g, double cx, double cy, double r, Color color) {
        g.setColor(color);
        int[] xs = new int[10];
        int[] ys = new int[10];
        for (int i = 0; i < 10; i++) {
            double rad = (i % 2 == 0) ? r : r * 0.4;
            double ang = Math.PI / 2 + i * Math.PI / 5;
            xs[i] = (int) Math.round(cx + rad * Math.cos(ang));
            ys[i] = (int) Math.round(cy - rad * Math.sin(ang));
        }
        g.fillPolygon(xs, ys, 10);
    }

    private String escapeDn(String s) {
        return s.replaceAll("[,+\"\\\\<>;=]", " ");
    }
}
