package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

@Slf4j
public final class Jbig2Converter {
    private Jbig2Converter() {
    }
    public static byte[] convertJbig2ToPng(byte[] jbig2Bytes) {
        if (jbig2Bytes == null || jbig2Bytes.length == 0) {
            return null;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(jbig2Bytes);
             ImageInputStream iis = ImageIO.createImageInputStream(bais)) {

            if (iis == null) {
                log.warn("创建 ImageInputStream 失败");
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JBIG2");
            if (readers == null || !readers.hasNext()) {
                readers = ImageIO.getImageReaders(iis);
            }

            if (readers == null || !readers.hasNext()) {
                log.warn("未找到 JBIG2 ImageReader，请确认已引入 jbig2-imageio 依赖");
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);

                BufferedImage image = reader.read(0);
                if (image == null) {
                    log.warn("JBIG2 解码结果为空");
                    return null;
                }
                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    boolean ok = ImageIO.write(image, "png", baos);
                    if (!ok) {
                        log.warn("ImageIO.write 输出 PNG 失败");
                        return null;
                    }
                    return baos.toByteArray();
                }
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.warn("JBIG2 转 PNG 失败: {}", e.getMessage(), e);
            return null;
        }
    }
    public static boolean isLikelyJbig2(byte[] bytes) {
        if (bytes == null || bytes.length < 8) return false;
        return (bytes[0] & 0xFF) == 0x97
                && bytes[1] == 0x4A
                && bytes[2] == 0x42
                && bytes[3] == 0x32
                && bytes[4] == 0x0D
                && bytes[5] == 0x0A
                && bytes[6] == 0x1A
                && bytes[7] == 0x0A;
    }
}