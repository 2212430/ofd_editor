package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.PageLayout;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.reader.OFDReader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ConversionService {

    private static final double OFD_RENDER_PPM = 150.0 / 25.4; // 150 DPI 转 像素/毫米
    private static final float PDF_RENDER_DPI = 150f;
    private static final double PAGE_WIDTH_MM = 210.0;
    private static final double PAGE_HEIGHT_MM = 297.0;

    // ─────────────────────────────────────────
    // OFD → PDF
    // ─────────────────────────────────────────
    public byte[] ofdToPdf(MultipartFile file) throws Exception {
        Path tempOfd = Files.createTempFile("ofd_in_", ".ofd");
        Path tempPdf = Files.createTempFile("pdf_out_", ".pdf");
        try {
            file.transferTo(tempOfd);
            log.info("开始 OFD->PDF 转换: {}", file.getOriginalFilename());

            try (OFDReader reader = new OFDReader(tempOfd);
                 PDDocument pdfDoc = new PDDocument()) {

                int pageCount = reader.getNumberOfPages();
                log.info("OFD 共 {} 页", pageCount);

                for (int i = 0; i < pageCount; i++) {
                    BufferedImage img = renderOfdPage(tempOfd, i + 1);
                    PDPage pdfPage = new PDPage(
                            new org.apache.pdfbox.pdmodel.common.PDRectangle(
                                    img.getWidth(), img.getHeight()));
                    pdfDoc.addPage(pdfPage);
                    PDImageXObject pdImg = LosslessFactory.createFromImage(pdfDoc, img);
                    try (PDPageContentStream cs = new PDPageContentStream(pdfDoc, pdfPage)) {
                        cs.drawImage(pdImg, 0, 0, img.getWidth(), img.getHeight());
                    }
                    log.debug("PDF 第 {} 页写入完成 {}x{}", i + 1, img.getWidth(), img.getHeight());
                }

                pdfDoc.save(tempPdf.toFile());
            }

            byte[] result = Files.readAllBytes(tempPdf);
            log.info("OFD->PDF 完成，输出大小: {} bytes", result.length);
            return result;

        } finally {
            Files.deleteIfExists(tempOfd);
            Files.deleteIfExists(tempPdf);
        }
    }

    // ─────────────────────────────────────────
    // PDF → OFD
    // ─────────────────────────────────────────
    public byte[] pdfToOfd(MultipartFile file) throws Exception {
        Path tempPdf = Files.createTempFile("pdf_in_", ".pdf");
        Path tempOfd = Files.createTempFile("ofd_out_", ".ofd");
        List<Path> tempImages = new ArrayList<>();
        try {
            file.transferTo(tempPdf);
            log.info("开始 PDF->OFD 转换: {}", file.getOriginalFilename());

            try (PDDocument pdfDoc = PDDocument.load(tempPdf.toFile());
                 OFDDoc ofdDoc = new OFDDoc(tempOfd)) {

                PDFRenderer renderer = new PDFRenderer(pdfDoc);
                int pageCount = pdfDoc.getNumberOfPages();
                log.info("PDF 共 {} 页", pageCount);

                for (int i = 0; i < pageCount; i++) {
                    BufferedImage pageImg = renderer.renderImageWithDPI(i, PDF_RENDER_DPI, ImageType.RGB);
                    Path imgPath = Files.createTempFile("page_", ".png");
                    tempImages.add(imgPath);
                    ImageIO.write(pageImg, "PNG", imgPath.toFile());

                    PageLayout layout = new PageLayout(PAGE_WIDTH_MM, PAGE_HEIGHT_MM);
                    VirtualPage vPage = new VirtualPage(layout);
                    Img elem = new Img(imgPath);
                    elem.setX(0d);
                    elem.setY(0d);
                    elem.setWidth(PAGE_WIDTH_MM);
                    elem.setHeight(PAGE_HEIGHT_MM);
                    vPage.add(elem);
                    ofdDoc.addVPage(vPage);
                    log.debug("OFD 第 {} 页写入完成", i + 1);
                }
            }

            byte[] result = Files.readAllBytes(tempOfd);
            log.info("PDF->OFD 完成，输出大小: {} bytes", result.length);
            return result;

        } finally {
            for (Path p : tempImages) Files.deleteIfExists(p);
            Files.deleteIfExists(tempPdf);
            Files.deleteIfExists(tempOfd);
        }
    }
    private BufferedImage renderOfdPage(Path ofdPath, int pageNum) throws Exception {
        // 优先尝试直接调用（避免反射开销）
        try {
            return renderDirect(ofdPath, pageNum);
        } catch (NoClassDefFoundError | ClassNotFoundException e) {
            throw new IllegalStateException(
                    "ofdrw-converter 不在 classpath，请检查 pom.xml", e);
        } catch (Exception e) {
            log.error("第 {} 页渲染失败: {}", pageNum, e.getMessage(), e);
            throw new IllegalStateException("OFD 第 " + pageNum + " 页渲染失败: " + e.getMessage(), e);
        }
    }

    private BufferedImage renderDirect(Path ofdPath, int pageNum) throws Exception {
        // pageNum 外部传入从 1 开始，这里转成 0-based index
        try (OFDReader reader = new OFDReader(ofdPath)) {
            org.ofdrw.converter.ImageMaker maker =
                    new org.ofdrw.converter.ImageMaker(reader, OFD_RENDER_PPM);
            BufferedImage img = maker.makePage(pageNum - 1); // ← 关键：减1
            if (img == null) {
                throw new IllegalStateException("makePage(" + (pageNum - 1) + ") 返回 null");
            }
            return img;
        }
    }
}