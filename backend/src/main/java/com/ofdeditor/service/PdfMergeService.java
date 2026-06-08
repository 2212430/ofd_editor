package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 在 PDF 层面合并/拆分文档（保留各页原生 PDF 内容，非栅格化）。
 */
@Slf4j
@Service
public class PdfMergeService {

    public record SplitPair(byte[] part1, byte[] part2) {}

    /**
     * @param splitAfterPage 第一部分最后一页页码（1-based）
     */
    public SplitPair splitPdf(byte[] pdfBytes, int splitAfterPage) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF 文件为空");
        }
        try (PDDocument src = PDDocument.load(pdfBytes)) {
            int total = src.getNumberOfPages();
            com.ofdeditor.util.SplitPayloadUtil.validateSplit(total, splitAfterPage);
            byte[] part1 = extractPageRange(src, 0, splitAfterPage);
            byte[] part2 = extractPageRange(src, splitAfterPage, total);
            log.info("PDF 拆分完成: 共 {} 页, 拆分点={}, 第一部分 {} 页, 第二部分 {} 页",
                    total, splitAfterPage, splitAfterPage, total - splitAfterPage);
            return new SplitPair(part1, part2);
        }
    }

    public int countPages(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            return doc.getNumberOfPages();
        }
    }

    private static byte[] extractPageRange(PDDocument src, int fromInclusive, int toExclusive) throws IOException {
        try (PDDocument dest = new PDDocument()) {
            for (int i = fromInclusive; i < toExclusive; i++) {
                dest.importPage(src.getPage(i));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            dest.save(out);
            return out.toByteArray();
        }
    }

    /**
     * @param firstPdf  第一个 PDF（页面在前）
     * @param secondPdf 第二个 PDF（页面在后）
     */
    public byte[] mergeTwoPdf(byte[] firstPdf, byte[] secondPdf) throws IOException {
        if (firstPdf == null || firstPdf.length == 0) {
            throw new IllegalArgumentException("第一个 PDF 为空");
        }
        if (secondPdf == null || secondPdf.length == 0) {
            throw new IllegalArgumentException("第二个 PDF 为空");
        }

        int n1 = pageCount(firstPdf);
        int n2 = pageCount(secondPdf);

        PDFMergerUtility merger = new PDFMergerUtility();
        try (ByteArrayInputStream in1 = new ByteArrayInputStream(firstPdf);
             ByteArrayInputStream in2 = new ByteArrayInputStream(secondPdf);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            merger.addSource(in1);
            merger.addSource(in2);
            merger.setDestinationStream(out);
            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
            byte[] result = out.toByteArray();
            log.info("PDF 合并完成: {} 页 + {} 页 → {} 页，输出 {} bytes", n1, n2, n1 + n2, result.length);
            return result;
        }
    }

    private static int pageCount(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            return doc.getNumberOfPages();
        }
    }
}
