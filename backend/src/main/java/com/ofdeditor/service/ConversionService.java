package com.ofdeditor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ofdrw.converter.export.HTMLExporter;
import org.ofdrw.converter.export.PDFExporterPDFBox;
import org.ofdrw.converter.export.TextExporter;
import org.ofdrw.converter.ofdconverter.PDFConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversionService {

    private final PdfToWordService pdfToWordService;
    private final PdfToPptService pdfToPptService;

    // ─────────────────────────────────────────
    // OFD → PDF（矢量/文本保留，ofdrw PdfboxMaker）
    // ─────────────────────────────────────────
    public byte[] ofdToPdf(MultipartFile file) throws Exception {
        Path tempOfd = Files.createTempFile("ofd_in_", ".ofd");
        Path tempPdf = Files.createTempFile("pdf_out_", ".pdf");
        try {
            file.transferTo(tempOfd);
            log.info("开始 OFD->PDF 矢量转换: {}", file.getOriginalFilename());

            try (PDFExporterPDFBox exporter = new PDFExporterPDFBox(tempOfd, tempPdf)) {
                exporter.export();
            }

            byte[] result = Files.readAllBytes(tempPdf);
            log.info("OFD->PDF 矢量转换完成，输出大小: {} bytes", result.length);
            return result;

        } finally {
            Files.deleteIfExists(tempOfd);
            Files.deleteIfExists(tempPdf);
        }
    }

    // ─────────────────────────────────────────
    // OFD → Word（先矢量转 PDF，再 pdf2docx）
    // ─────────────────────────────────────────
    public byte[] ofdToWord(MultipartFile file) throws Exception {
        Path tempOfd = Files.createTempFile("ofd_in_", ".ofd");
        Path tempPdf = Files.createTempFile("pdf_mid_", ".pdf");
        try {
            file.transferTo(tempOfd);
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.ofd";
            log.info("开始 OFD->Word: {}", name);

            try (PDFExporterPDFBox exporter = new PDFExporterPDFBox(tempOfd, tempPdf)) {
                exporter.export();
            }

            byte[] pdfBytes = Files.readAllBytes(tempPdf);
            String pdfLogName = name.replaceAll("(?i)\\.ofd$", "") + ".pdf";
            byte[] docx = pdfToWordService.convertBytes(pdfBytes, pdfLogName);
            log.info("OFD->Word 完成，输出大小: {} bytes", docx.length);
            return docx;
        } finally {
            Files.deleteIfExists(tempOfd);
            Files.deleteIfExists(tempPdf);
        }
    }

    // ─────────────────────────────────────────
    // OFD → PPT（先矢量转 PDF，再每页渲染为幻灯片图片）
    // ─────────────────────────────────────────
    public byte[] ofdToPpt(MultipartFile file) throws Exception {
        Path tempOfd = Files.createTempFile("ofd_in_", ".ofd");
        Path tempPdf = Files.createTempFile("pdf_mid_", ".pdf");
        try {
            file.transferTo(tempOfd);
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.ofd";
            log.info("开始 OFD->PPT: {}", name);

            try (PDFExporterPDFBox exporter = new PDFExporterPDFBox(tempOfd, tempPdf)) {
                exporter.export();
            }

            byte[] pdfBytes = Files.readAllBytes(tempPdf);
            String pdfLogName = name.replaceAll("(?i)\\.ofd$", "") + ".pdf";
            byte[] pptx = pdfToPptService.convertBytes(pdfBytes, pdfLogName);
            log.info("OFD->PPT 完成，输出大小: {} bytes", pptx.length);
            return pptx;
        } finally {
            Files.deleteIfExists(tempOfd);
            Files.deleteIfExists(tempPdf);
        }
    }

    // ─────────────────────────────────────────
    // OFD → 纯文本（ofdrw TextExporter）
    // ─────────────────────────────────────────
    public byte[] ofdToText(MultipartFile file) throws Exception {
        Path tempOfd = Files.createTempFile("ofd_in_", ".ofd");
        Path tempTxt = Files.createTempFile("txt_out_", ".txt");
        try {
            file.transferTo(tempOfd);
            log.info("开始 OFD->文本: {}", file.getOriginalFilename());
            try (TextExporter exporter = new TextExporter(tempOfd, tempTxt)) {
                exporter.export();
            }
            byte[] result = Files.readAllBytes(tempTxt);
            log.info("OFD->文本 完成，输出大小: {} bytes", result.length);
            return result;
        } finally {
            Files.deleteIfExists(tempOfd);
            Files.deleteIfExists(tempTxt);
        }
    }

    // ─────────────────────────────────────────
    // OFD → HTML（ofdrw HTMLExporter，SVG 嵌入单页 HTML）
    // ─────────────────────────────────────────
    public byte[] ofdToHtml(MultipartFile file) throws Exception {
        Path tempOfd = Files.createTempFile("ofd_in_", ".ofd");
        Path tempHtml = Files.createTempFile("html_out_", ".html");
        try {
            file.transferTo(tempOfd);
            log.info("开始 OFD->HTML: {}", file.getOriginalFilename());
            try (HTMLExporter exporter = new HTMLExporter(tempOfd, tempHtml)) {
                exporter.export();
            }
            byte[] result = Files.readAllBytes(tempHtml);
            log.info("OFD->HTML 完成，输出大小: {} bytes", result.length);
            return result;
        } finally {
            Files.deleteIfExists(tempOfd);
            Files.deleteIfExists(tempHtml);
        }
    }

    // ─────────────────────────────────────────
    // PDF → OFD（ofdrw PDFConverter：Graphics2D 矢量路径，含书签/附件）
    // ─────────────────────────────────────────
    public byte[] pdfToOfd(MultipartFile file) throws Exception {
        Path tempPdf = Files.createTempFile("pdf_in_", ".pdf");
        Path tempOfd = Files.createTempFile("ofd_out_", ".ofd");
        try {
            file.transferTo(tempPdf);
            log.info("开始 PDF->OFD 矢量转换(ofdrw PDFConverter): {}", file.getOriginalFilename());

            try (PDFConverter converter = new PDFConverter(tempOfd)) {
                converter.convert(tempPdf);
            }

            byte[] result = Files.readAllBytes(tempOfd);
            log.info("PDF->OFD 矢量转换完成，输出大小: {} bytes", result.length);
            return result;

        } finally {
            Files.deleteIfExists(tempPdf);
            Files.deleteIfExists(tempOfd);
        }
    }
}
