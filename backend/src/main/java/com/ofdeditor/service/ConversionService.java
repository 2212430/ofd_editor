package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.ofdrw.converter.export.PDFExporterPDFBox;
import org.ofdrw.layout.OFDDoc;
import org.ofdrw.layout.PageLayout;
import org.ofdrw.layout.VirtualPage;
import org.ofdrw.layout.element.Img;
import org.ofdrw.layout.element.Position;
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

    private static final float PDF_RENDER_DPI = 150f;
    private static final double PT_TO_MM = 25.4 / 72.0;

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
                    var pdfPage = pdfDoc.getPage(i);
                    var mediaBox = pdfPage.getMediaBox();
                    double widthMm = mediaBox.getWidth() * PT_TO_MM;
                    double heightMm = mediaBox.getHeight() * PT_TO_MM;

                    BufferedImage pageImg = renderer.renderImageWithDPI(i, PDF_RENDER_DPI, ImageType.RGB);
                    Path imgPath = Files.createTempFile("page_", ".png");
                    tempImages.add(imgPath);
                    ImageIO.write(pageImg, "PNG", imgPath.toFile());

                    PageLayout layout = new PageLayout(widthMm, heightMm);
                    VirtualPage vPage = new VirtualPage(layout);
                    Img elem = new Img(imgPath);
                    elem.setPosition(Position.Absolute);
                    elem.setX(0d);
                    elem.setY(0d);
                    elem.setWidth(widthMm);
                    elem.setHeight(heightMm);
                    vPage.add(elem);
                    ofdDoc.addVPage(vPage);
                    log.debug("OFD 第 {} 页写入完成 {}x{} mm", i + 1,
                            String.format("%.1f", widthMm), String.format("%.1f", heightMm));
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
}
