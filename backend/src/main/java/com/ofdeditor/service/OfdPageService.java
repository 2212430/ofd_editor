package com.ofdeditor.service;

import com.ofdeditor.dto.WatermarkDTO;
import lombok.extern.slf4j.Slf4j;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.edit.Watermark;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.tool.merge.OFDMerger;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * OFD 页面级操作：文本水印（复用 ofdrw 烘焙）、页面范围提取/重排（OFDMerger）。
 */
@Slf4j
@Service
public class OfdPageService {

    private static final double PT_TO_MM = 25.4 / 72.0;

    /**
     * 给 OFD 全部页面加文本水印，返回新的 OFD 字节。
     */
    public byte[] addWatermark(byte[] ofdBytes, WatermarkDTO wm) throws Exception {
        if (wm == null || wm.getText() == null || wm.getText().isBlank()) {
            return ofdBytes;
        }
        Path src = Files.createTempFile("ofd-wm-src-", ".ofd");
        Path out = Files.createTempFile("ofd-wm-out-", ".ofd");
        try {
            Files.write(src, ofdBytes);
            Files.deleteIfExists(out); // 交给 ofdrw 创建目标包

            try (OFDReader reader = new OFDReader(src);
                 OFDDoc doc = new OFDDoc(reader, out)) {

                double fontMm = (wm.getFontSize() != null ? wm.getFontSize() : 36.0) * PT_TO_MM;
                double angle = wm.getAngle() != null ? wm.getAngle() : 45.0;
                double alpha = wm.getOpacity() != null ? wm.getOpacity() : 0.18;
                String color = (wm.getColor() != null && !wm.getColor().isBlank())
                        ? wm.getColor() : "#999999";

                Watermark watermark = new Watermark(doc.getPageLayout())
                        .setValue(wm.getText())
                        .setColor(color)
                        .setFontSize(fontMm)
                        .setAngle(angle)
                        .setGlobalAlpha(alpha);
                if (Boolean.TRUE.equals(wm.getBold())) {
                    watermark.setBold(true);
                }
                doc.addWatermark(watermark);
            }
            byte[] result = Files.readAllBytes(out);
            log.info("OFD 水印完成，大小 {} bytes", result.length);
            return result;
        } finally {
            safeDelete(src);
            safeDelete(out);
        }
    }

    /**
     * 按页号（1 基，可乱序/去重由调用方决定）提取/重排页面，生成新的 OFD。
     */
    public byte[] extractPages(byte[] ofdBytes, List<Integer> pageNumbers1Based) throws Exception {
        if (pageNumbers1Based == null || pageNumbers1Based.isEmpty()) {
            throw new IllegalArgumentException("提取页码为空");
        }
        int[] pages = pageNumbers1Based.stream()
                .filter(n -> n != null && n >= 1)
                .mapToInt(Integer::intValue)
                .toArray();
        if (pages.length == 0) {
            throw new IllegalArgumentException("提取页码无有效值");
        }

        Path src = Files.createTempFile("ofd-ext-src-", ".ofd");
        Path out = Files.createTempFile("ofd-ext-out-", ".ofd");
        try {
            Files.write(src, ofdBytes);
            Files.deleteIfExists(out);

            try (OFDMerger merger = new OFDMerger(out)) {
                merger.add(src, pages);
            }
            byte[] result = Files.readAllBytes(out);
            log.info("OFD 提取完成：{} 页 -> {} bytes", pages.length, result.length);
            return result;
        } finally {
            safeDelete(src);
            safeDelete(out);
        }
    }

    private void safeDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
            // best-effort
        }
    }
}
