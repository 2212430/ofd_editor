package com.ofdeditor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofdeditor.dto.AnnotationDTO;
import com.ofdeditor.dto.OfdDocumentDTO;
import com.ofdeditor.dto.PageDTO;
import com.ofdeditor.dto.PdfExportRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLine;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 原生 PDF 支持：不再栅格化为图片再转 OFD，而是
 *  1) 解析每页可视尺寸（mm），交给前端用 PDF.js 直接渲染（保留矢量/文字/字体）；
 *  2) 把用户注释以非破坏方式（追加内容流）烘焙回 PDF。
 *
 * 注释坐标系：与前端画布一致——以「可视页面」左上角为原点，x 向右、y 向下，单位 mm。
 * PDF 用户空间：以未旋转 MediaBox 左下角为原点，x 向右、y 向上，单位 pt，
 * 阅读器再按 /Rotate 顺时针旋转显示。这里用仿射变换 T 完成两套坐标的映射。
 */
@Slf4j
@Service
public class PdfNativeService {

    private static final double MM_TO_PT = 72.0 / 25.4;
    private static final double PT_TO_MM = 25.4 / 72.0;
    /** 注释线宽在前端是屏幕像素（96dpi），换算到 pt */
    private static final double PX_TO_PT = 72.0 / 96.0;
    private static final double KAPPA = 0.5522847498;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 由本系统「接管」的注释子类型：导入到编辑器、导出时从模型重绘（避免与原页重复） */
    private static final Set<String> MANAGED_SUBTYPES = new HashSet<>(Arrays.asList(
            "Highlight", "Underline", "StrikeOut", "Squiggly",
            "Square", "Circle", "Line", "Ink", "FreeText", "Text"
    ));

    // ==================== 解析 ====================

    /** 解析 PDF，仅产出页面尺寸等元信息（渲染交给前端 PDF.js） */
    public OfdDocumentDTO parse(byte[] pdfBytes, String title) throws Exception {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            List<PageDTO> pages = new ArrayList<>(pageCount);

            for (int i = 0; i < pageCount; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox();
                int rot = normalizeRotation(page.getRotation());

                double wPt = box.getWidth();
                double hPt = box.getHeight();
                double visualWpt = (rot == 90 || rot == 270) ? hPt : wPt;
                double visualHpt = (rot == 90 || rot == 270) ? wPt : hPt;

                PageDTO p = new PageDTO();
                p.setPageIndex(i);
                p.setSourcePageIndex(i);
                p.setWidth(visualWpt * PT_TO_MM);
                p.setHeight(visualHpt * PT_TO_MM);
                p.setElements(new ArrayList<>());
                pages.add(p);
            }

            OfdDocumentDTO dto = new OfdDocumentDTO();
            dto.setTitle(title != null ? title : "document");
            dto.setAuthor("未知");
            dto.setPageCount(pageCount);
            dto.setPages(pages);
            return dto;
        }
    }

    // ==================== 解析已有注释 ====================

    private static final Pattern DA_FONT_SIZE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s+Tf");

    /**
     * 读取 PDF 内已有的批注，转换为统一注释模型（按页分组）。
     * 支持：高亮/下划线/删除线/波浪线、矩形、圆形、直线（→箭头）、手绘、文本框、便利贴。
     * 不支持的（图章外观、表单控件、链接、签名等）跳过。
     */
    public Map<Integer, List<AnnotationDTO>> parseExistingAnnotations(byte[] pdfBytes) {
        Map<Integer, List<AnnotationDTO>> result = new HashMap<>();
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                Transform tf = Transform.of(page);
                List<AnnotationDTO> list = new ArrayList<>();
                for (PDAnnotation ann : page.getAnnotations()) {
                    try {
                        AnnotationDTO dto = convertExisting(ann, tf, i);
                        if (dto != null) list.add(dto);
                    } catch (Exception e) {
                        log.debug("解析注释失败 page={} subtype={}: {}",
                                i, ann.getSubtype(), e.getMessage());
                    }
                }
                if (!list.isEmpty()) result.put(i, list);
            }
        } catch (Exception e) {
            log.warn("解析 PDF 已有注释失败: {}", e.getMessage());
        }
        return result;
    }

    private AnnotationDTO convertExisting(PDAnnotation ann, Transform tf, int pageIndex) {
        String sub = ann.getSubtype();
        if (sub == null) return null;
        PDRectangle rect = ann.getRectangle();

        AnnotationDTO dto = new AnnotationDTO();
        dto.setId(UUID.randomUUID().toString());
        dto.setPageIndex(pageIndex);
        long now = System.currentTimeMillis();
        dto.setCreatedAt(now);
        dto.setUpdatedAt(now);

        switch (sub) {
            case "Highlight": {
                if (rect == null) return null;
                applyRect(dto, tf, rect);
                dto.setType("HIGHLIGHT");
                dto.setColor(markupColor(ann, "#FFFF00"));
                dto.setStrokeColor("transparent");
                dto.setOpacity(markupOpacity(ann, 0.4));
                return dto;
            }
            case "Underline":
            case "Squiggly": {
                if (rect == null) return null;
                applyRect(dto, tf, rect);
                dto.setType("UNDERLINE");
                dto.setStrokeColor(markupColor(ann, "#000000"));
                dto.setColor(dto.getStrokeColor());
                dto.setLineWidth(lineWidthPx(ann, 2.0));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "StrikeOut": {
                if (rect == null) return null;
                applyRect(dto, tf, rect);
                dto.setType("STRIKEOUT");
                dto.setStrokeColor(markupColor(ann, "#FF0000"));
                dto.setColor(dto.getStrokeColor());
                dto.setLineWidth(lineWidthPx(ann, 2.0));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "Square": {
                if (rect == null) return null;
                applyRect(dto, tf, rect);
                dto.setType("RECTANGLE");
                dto.setStrokeColor(annColor(ann, "#000000"));
                dto.setColor(dto.getStrokeColor());
                dto.setLineWidth(lineWidthPx(ann, 2.0));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "Circle": {
                if (rect == null) return null;
                applyRect(dto, tf, rect);
                dto.setType("CIRCLE");
                dto.setStrokeColor(annColor(ann, "#000000"));
                dto.setColor(dto.getStrokeColor());
                dto.setLineWidth(lineWidthPx(ann, 2.0));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "Line": {
                float[] l = (ann instanceof PDAnnotationLine) ? ((PDAnnotationLine) ann).getLine() : null;
                if (l == null || l.length < 4) {
                    if (rect == null) return null;
                    applyRect(dto, tf, rect);
                } else {
                    float[] p1 = tf.invPt(l[0], l[1]);
                    float[] p2 = tf.invPt(l[2], l[3]);
                    double minx = Math.min(p1[0], p2[0]);
                    double miny = Math.min(p1[1], p2[1]);
                    dto.setX(minx * PT_TO_MM);
                    dto.setY(miny * PT_TO_MM);
                    dto.setWidth(Math.abs(p2[0] - p1[0]) * PT_TO_MM);
                    dto.setHeight(Math.abs(p2[1] - p1[1]) * PT_TO_MM);
                    double[][] pts = {
                            {(p1[0] - minx) * PT_TO_MM, (p1[1] - miny) * PT_TO_MM},
                            {(p2[0] - minx) * PT_TO_MM, (p2[1] - miny) * PT_TO_MM},
                    };
                    dto.setPathPoints(writePoints(pts));
                }
                dto.setType("ARROW");
                dto.setStrokeColor(annColor(ann, "#000000"));
                dto.setColor(dto.getStrokeColor());
                dto.setLineWidth(lineWidthPx(ann, 2.0));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "Ink": {
                double[][] pts = readInkPoints(ann, tf);
                if (pts.length < 2) return null;
                double minx = Double.MAX_VALUE, miny = Double.MAX_VALUE;
                double maxx = -Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
                for (double[] p : pts) {
                    minx = Math.min(minx, p[0]); miny = Math.min(miny, p[1]);
                    maxx = Math.max(maxx, p[0]); maxy = Math.max(maxy, p[1]);
                }
                dto.setX(minx * PT_TO_MM);
                dto.setY(miny * PT_TO_MM);
                dto.setWidth((maxx - minx) * PT_TO_MM);
                dto.setHeight((maxy - miny) * PT_TO_MM);
                double[][] rel = new double[pts.length][2];
                for (int k = 0; k < pts.length; k++) {
                    rel[k][0] = (pts[k][0] - minx) * PT_TO_MM;
                    rel[k][1] = (pts[k][1] - miny) * PT_TO_MM;
                }
                dto.setType("FREEHAND");
                dto.setPathPoints(writePoints(rel));
                dto.setStrokeColor(annColor(ann, "#000000"));
                dto.setColor(dto.getStrokeColor());
                dto.setLineWidth(lineWidthPx(ann, 2.0));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "FreeText": {
                if (rect == null) return null;
                applyRect(dto, tf, rect);
                dto.setType("TEXTBOX");
                dto.setContent(ann.getContents());
                dto.setColor("transparent");
                dto.setStrokeColor("#999999");
                dto.setLineWidth(1.0);
                dto.setFontColor("#000000");
                dto.setFontSize(freeTextFontMm(ann));
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            case "Text": {
                // 便利贴：图标位置较小，给一个默认显示尺寸
                double x = 0, y = 0;
                if (rect != null) {
                    float[] tl = tf.invPt(rect.getLowerLeftX(), rect.getUpperRightY());
                    x = tl[0] * PT_TO_MM;
                    y = tl[1] * PT_TO_MM;
                }
                dto.setType("STICKYNOTE");
                dto.setX(x);
                dto.setY(y);
                dto.setWidth(50.0);
                dto.setHeight(30.0);
                dto.setContent(ann.getContents());
                dto.setColor("#FFFACD");
                dto.setStrokeColor("#E6C619");
                dto.setLineWidth(1.0);
                dto.setFontColor("#333333");
                dto.setFontSize(3.5);
                dto.setOpacity(markupOpacity(ann, 1.0));
                return dto;
            }
            default:
                // Stamp / Widget / Link / Popup / Signature 等：跳过
                return null;
        }
    }

    /** 移除页面中由本系统接管的注释子类型，保留链接/表单/图章等其它注释 */
    private void stripManagedAnnotations(PDPage page) {
        try {
            List<PDAnnotation> anns = page.getAnnotations();
            if (anns == null || anns.isEmpty()) return;
            List<PDAnnotation> keep = new ArrayList<>();
            for (PDAnnotation a : anns) {
                String sub = a.getSubtype();
                if (sub == null || !MANAGED_SUBTYPES.contains(sub)) {
                    keep.add(a);
                }
            }
            if (keep.size() != anns.size()) {
                page.setAnnotations(keep);
            }
        } catch (Exception e) {
            log.debug("清理原注释失败: {}", e.getMessage());
        }
    }

    private void applyRect(AnnotationDTO dto, Transform tf, PDRectangle r) {
        float[] a = tf.invPt(r.getLowerLeftX(), r.getLowerLeftY());
        float[] b = tf.invPt(r.getUpperRightX(), r.getUpperRightY());
        dto.setX(Math.min(a[0], b[0]) * PT_TO_MM);
        dto.setY(Math.min(a[1], b[1]) * PT_TO_MM);
        dto.setWidth(Math.abs(b[0] - a[0]) * PT_TO_MM);
        dto.setHeight(Math.abs(b[1] - a[1]) * PT_TO_MM);
    }

    private double[][] readInkPoints(PDAnnotation ann, Transform tf) {
        try {
            COSBase inkList = ann.getCOSObject().getDictionaryObject(COSName.getPDFName("InkList"));
            if (!(inkList instanceof COSArray)) return new double[0][];
            List<double[]> out = new ArrayList<>();
            for (COSBase strokeBase : (COSArray) inkList) {
                if (!(strokeBase instanceof COSArray)) continue;
                COSArray stroke = (COSArray) strokeBase;
                for (int i = 0; i + 1 < stroke.size(); i += 2) {
                    float ux = ((COSNumber) stroke.getObject(i)).floatValue();
                    float uy = ((COSNumber) stroke.getObject(i + 1)).floatValue();
                    float[] v = tf.invPt(ux, uy);
                    out.add(new double[]{v[0], v[1]});
                }
            }
            return out.toArray(new double[0][]);
        } catch (Exception e) {
            return new double[0][];
        }
    }

    private String writePoints(double[][] pts) {
        try {
            return objectMapper.writeValueAsString(pts);
        } catch (Exception e) {
            return null;
        }
    }

    private double freeTextFontMm(PDAnnotation ann) {
        try {
            COSBase da = ann.getCOSObject().getDictionaryObject(COSName.DA);
            if (da != null) {
                Matcher m = DA_FONT_SIZE.matcher(da.toString());
                if (m.find()) {
                    double pt = Double.parseDouble(m.group(1));
                    if (pt > 0) return pt * PT_TO_MM;
                }
            }
        } catch (Exception ignore) {
        }
        return 4.0;
    }

    private String markupColor(PDAnnotation ann, String fallback) {
        if (ann instanceof PDAnnotationMarkup) {
            String hex = colorHex(((PDAnnotationMarkup) ann).getColor());
            if (hex != null) return hex;
        }
        return annColor(ann, fallback);
    }

    private String annColor(PDAnnotation ann, String fallback) {
        try {
            if (ann instanceof PDAnnotationSquareCircle) {
                String hex = colorHex(((PDAnnotationSquareCircle) ann).getColor());
                if (hex != null) return hex;
            } else if (ann instanceof PDAnnotationLine) {
                String hex = colorHex(((PDAnnotationLine) ann).getColor());
                if (hex != null) return hex;
            } else if (ann instanceof PDAnnotationMarkup) {
                String hex = colorHex(((PDAnnotationMarkup) ann).getColor());
                if (hex != null) return hex;
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private double markupOpacity(PDAnnotation ann, double fallback) {
        try {
            if (ann instanceof PDAnnotationMarkup) {
                float ca = ((PDAnnotationMarkup) ann).getConstantOpacity();
                if (ca > 0 && ca <= 1) return ca;
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private double lineWidthPx(PDAnnotation ann, double fallback) {
        try {
            COSBase bs = ann.getCOSObject().getDictionaryObject(COSName.BS);
            if (bs instanceof COSDictionary) {
                float w = ((COSDictionary) bs).getFloat(COSName.W, -1f);
                if (w >= 0) return Math.max(0.5, w * 96.0 / 72.0);
            }
            COSBase border = ann.getCOSObject().getDictionaryObject(COSName.BORDER);
            if (border instanceof COSArray && ((COSArray) border).size() >= 3) {
                COSBase wB = ((COSArray) border).getObject(2);
                if (wB instanceof COSNumber) {
                    return Math.max(0.5, ((COSNumber) wB).floatValue() * 96.0 / 72.0);
                }
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private String colorHex(PDColor color) {
        if (color == null) return null;
        try {
            float[] rgb = color.getColorSpace().toRGB(color.getComponents());
            int r = Math.round(rgb[0] * 255);
            int g = Math.round(rgb[1] * 255);
            int b = Math.round(rgb[2] * 255);
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 注释烘焙 ====================

    /** 把注释以非破坏方式（APPEND 内容流）烘焙进原 PDF（页序不变），返回新 PDF 字节 */
    public byte[] bakeAnnotations(byte[] pdfBytes,
                                  Map<Integer, List<AnnotationDTO>> annotationsByPage) throws Exception {
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            if (annotationsByPage != null) {
                for (Map.Entry<Integer, List<AnnotationDTO>> entry : annotationsByPage.entrySet()) {
                    int pageIndex = entry.getKey();
                    List<AnnotationDTO> anns = entry.getValue();
                    if (anns == null || anns.isEmpty()) continue;
                    if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) continue;

                    PDPage page = doc.getPage(pageIndex);
                    // 去掉原页中本系统接管的注释，统一由模型重绘，避免重复
                    stripManagedAnnotations(page);
                    Transform tf = Transform.of(page);
                    drawPageAnnotations(doc, page, tf, anns);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 按前端给出的页面布局重建页序（重排/删除/插入空白/复制页），再烘焙注释。
     * 支持「向 OFD 看齐」的页面操作导出。
     */
    public byte[] exportWithLayout(byte[] pdfBytes, PdfExportRequest req) throws Exception {
        List<PdfExportRequest.PageLayout> layout = req != null ? req.getPages() : null;
        Map<Integer, List<AnnotationDTO>> annotations =
                req != null ? req.getAnnotations() : null;

        // 没有布局信息时退化为原序烘焙
        if (layout == null || layout.isEmpty()) {
            return bakeAnnotations(pdfBytes, annotations);
        }

        try (PDDocument src = PDDocument.load(pdfBytes);
             PDDocument out = new PDDocument()) {

            int srcPageCount = src.getNumberOfPages();

            for (int i = 0; i < layout.size(); i++) {
                PdfExportRequest.PageLayout pl = layout.get(i);
                Integer srcIdx = pl.getSourceIndex();

                PDPage outPage;
                if (srcIdx != null && srcIdx >= 0 && srcIdx < srcPageCount) {
                    // 导入原始页（保留矢量/文字），importPage 返回加入 out 的克隆页
                    outPage = out.importPage(src.getPage(srcIdx));
                    // 去掉被接管的原注释（链接/表单/图章等保留），由模型统一重绘
                    stripManagedAnnotations(outPage);
                } else {
                    // 插入空白页（按 mm 尺寸）
                    double wPt = (pl.getWidthMm() != null ? pl.getWidthMm() : 210.0) * MM_TO_PT;
                    double hPt = (pl.getHeightMm() != null ? pl.getHeightMm() : 297.0) * MM_TO_PT;
                    outPage = new PDPage(new PDRectangle((float) wPt, (float) hPt));
                    out.addPage(outPage);
                }

                List<AnnotationDTO> anns = annotations != null ? annotations.get(i) : null;
                if (anns != null && !anns.isEmpty()) {
                    Transform tf = Transform.of(outPage);
                    drawPageAnnotations(out, outPage, tf, anns);
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            out.save(baos);
            return baos.toByteArray();
        }
    }

    private void drawPageAnnotations(PDDocument doc, PDPage page, Transform tf,
                                     List<AnnotationDTO> anns) {
        for (AnnotationDTO ann : anns) {
            if (Boolean.TRUE.equals(ann.getHidden())) continue;
            try {
                drawAnnotation(doc, page, tf, ann);
            } catch (Exception e) {
                log.warn("烘焙注释失败 id={} type={}: {}",
                        ann.getId(), ann.getType(), e.getMessage());
            }
        }
    }

    private void drawAnnotation(PDDocument doc, PDPage page, Transform tf, AnnotationDTO ann)
            throws Exception {
        String type = ann.getType() == null ? "" : ann.getType();
        double x = nz(ann.getX());
        double y = nz(ann.getY());
        double w = nz(ann.getWidth());
        double h = nz(ann.getHeight());
        double opacity = ann.getOpacity() != null ? ann.getOpacity() : 1.0;
        double lineWidthPt = (ann.getLineWidth() != null ? ann.getLineWidth() : 2.0) * PX_TO_PT;

        switch (type) {
            case "HIGHLIGHT": {
                try (PDPageContentStream cs = open(doc, page)) {
                    applyAlpha(doc, cs, opacity);
                    float[] c = rgb(ann.getColor(), new float[]{1f, 1f, 0f});
                    cs.setNonStrokingColor(c[0], c[1], c[2]);
                    rect(cs, tf, x, y, w, h);
                    cs.fill();
                }
                break;
            }
            case "RECTANGLE": {
                try (PDPageContentStream cs = open(doc, page)) {
                    applyAlpha(doc, cs, opacity);
                    float[] c = rgb(firstNonNull(ann.getStrokeColor(), ann.getColor()), new float[]{0f, 0f, 0f});
                    cs.setStrokingColor(c[0], c[1], c[2]);
                    cs.setLineWidth((float) lineWidthPt);
                    rect(cs, tf, x, y, w, h);
                    cs.stroke();
                }
                break;
            }
            case "CIRCLE": {
                try (PDPageContentStream cs = open(doc, page)) {
                    applyAlpha(doc, cs, opacity);
                    float[] c = rgb(firstNonNull(ann.getStrokeColor(), ann.getColor()), new float[]{0f, 0f, 0f});
                    cs.setStrokingColor(c[0], c[1], c[2]);
                    cs.setLineWidth((float) lineWidthPt);
                    ellipse(cs, tf, x, y, w, h);
                    cs.stroke();
                }
                break;
            }
            case "UNDERLINE": {
                double ly = y + h;
                strokeLine(doc, page, tf, x, ly, x + w, ly,
                        firstNonNull(ann.getStrokeColor(), ann.getColor()),
                        new float[]{0f, 0f, 0f}, lineWidthPt, opacity);
                break;
            }
            case "STRIKEOUT": {
                double ly = y + h / 2.0;
                strokeLine(doc, page, tf, x, ly, x + w, ly,
                        firstNonNull(ann.getStrokeColor(), ann.getColor()),
                        new float[]{1f, 0f, 0f}, lineWidthPt, opacity);
                break;
            }
            case "ARROW": {
                double[][] pts = parsePoints(ann.getPathPoints());
                double[] start, end;
                if (pts.length >= 2) {
                    start = new double[]{x + pts[0][0], y + pts[0][1]};
                    end = new double[]{x + pts[1][0], y + pts[1][1]};
                } else {
                    start = new double[]{x, y};
                    end = new double[]{x + w, y + h};
                }
                drawArrow(doc, page, tf, start, end,
                        firstNonNull(ann.getStrokeColor(), ann.getColor()),
                        lineWidthPt, opacity);
                break;
            }
            case "FREEHAND": {
                double[][] pts = parsePoints(ann.getPathPoints());
                if (pts.length >= 2) {
                    try (PDPageContentStream cs = open(doc, page)) {
                        applyAlpha(doc, cs, opacity);
                        float[] c = rgb(firstNonNull(ann.getStrokeColor(), ann.getColor()), new float[]{0f, 0f, 0f});
                        cs.setStrokingColor(c[0], c[1], c[2]);
                        cs.setLineWidth((float) lineWidthPt);
                        cs.setLineCapStyle(1);
                        cs.setLineJoinStyle(1);
                        float[] p0 = tf.pt(x + pts[0][0], y + pts[0][1]);
                        cs.moveTo(p0[0], p0[1]);
                        for (int i = 1; i < pts.length; i++) {
                            float[] pi = tf.pt(x + pts[i][0], y + pts[i][1]);
                            cs.lineTo(pi[0], pi[1]);
                        }
                        cs.stroke();
                    }
                }
                break;
            }
            case "STAMP": {
                drawImageAnnotation(doc, page, tf, ann, x, y, w, h, opacity);
                break;
            }
            case "TEXTBOX":
            case "STICKYNOTE": {
                drawTextBox(doc, page, tf, ann, x, y, w, h, opacity,
                        "STICKYNOTE".equals(type));
                break;
            }
            default:
                log.debug("跳过未知注释类型: {}", type);
        }
    }

    private void drawImageAnnotation(PDDocument doc, PDPage page, Transform tf, AnnotationDTO ann,
                                     double x, double y, double w, double h, double opacity)
            throws Exception {
        String b64 = ann.getStampBase64();
        if (b64 == null || b64.isEmpty()) return;
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma >= 0) b64 = b64.substring(comma + 1);
        byte[] imgBytes = Base64.getDecoder().decode(b64.trim());
        PDImageXObject image = PDImageXObject.createFromByteArray(doc, imgBytes, ann.getId());

        double vx0 = x * MM_TO_PT;
        double vy0 = y * MM_TO_PT;
        double wv = w * MM_TO_PT;
        double hv = h * MM_TO_PT;

        Matrix m = tf.imageMatrix(vx0, vy0, wv, hv);
        try (PDPageContentStream cs = open(doc, page)) {
            applyAlpha(doc, cs, opacity);
            cs.drawImage(image, m);
        }
    }

    private void drawTextBox(PDDocument doc, PDPage page, Transform tf, AnnotationDTO ann,
                             double x, double y, double w, double h, double opacity,
                             boolean sticky) throws Exception {
        try (PDPageContentStream cs = open(doc, page)) {
            applyAlpha(doc, cs, opacity);
            float[] fill = rgb(ann.getColor(), sticky ? new float[]{1f, 0.98f, 0.80f} : null);
            float[] stroke = rgb(ann.getStrokeColor(), sticky ? new float[]{0.90f, 0.78f, 0.10f}
                    : new float[]{0.67f, 0.67f, 0.67f});
            boolean doFill = fill != null && !isTransparent(ann.getColor());
            boolean doStroke = stroke != null;
            if (doFill) cs.setNonStrokingColor(fill[0], fill[1], fill[2]);
            if (doStroke) {
                cs.setStrokingColor(stroke[0], stroke[1], stroke[2]);
                cs.setLineWidth((float) ((ann.getLineWidth() != null ? ann.getLineWidth() : 1.0) * PX_TO_PT));
            }
            rect(cs, tf, x, y, w, h);
            if (doFill && doStroke) cs.fillAndStroke();
            else if (doFill) cs.fill();
            else if (doStroke) cs.stroke();
        }

        String content = ann.getContent();
        if (content == null || content.isEmpty()) return;

        org.apache.pdfbox.pdmodel.font.PDFont font = FontProvider.cjkFont(doc);
        if (font == null) {
            log.warn("无可用中文字体，跳过文本注释内容绘制");
            return;
        }
        double fontMm = ann.getFontSize() != null ? ann.getFontSize() : 12.0;
        float fontPt = (float) (fontMm * MM_TO_PT);
        float[] fc = rgb(ann.getFontColor(), new float[]{0f, 0f, 0f});

        double padMm = sticky ? 1.6 : 1.1;
        double innerWmm = Math.max(0, w - padMm * 2);
        List<String> lines = wrapText(content, font, fontPt, innerWmm * MM_TO_PT);

        double lineHeightMm = fontMm * 1.2;
        try (PDPageContentStream cs = open(doc, page)) {
            applyAlpha(doc, cs, 1.0);
            cs.setNonStrokingColor(fc[0], fc[1], fc[2]);
            cs.beginText();
            cs.setFont(font, fontPt);
            // 文本基线：从框顶 padding 开始向下排版（可视坐标），首行基线再下移约 0.8em
            double baselineVy = y + padMm + fontMm * 0.85;
            float[] start = tf.pt(x + padMm, baselineVy);
            // 旋转页通过 text matrix 处理方向
            cs.setTextMatrix(tf.textMatrix(start[0], start[1]));
            double lead = lineHeightMm * MM_TO_PT;
            cs.setLeading(lead);
            boolean first = true;
            for (String ln : lines) {
                if ((baselineVy - y) > h) break;
                if (first) { cs.showText(safe(font, ln)); first = false; }
                else { cs.newLine(); cs.showText(safe(font, ln)); }
                baselineVy += lineHeightMm;
            }
            cs.endText();
        }
    }

    // ==================== 绘制辅助 ====================

    private PDPageContentStream open(PDDocument doc, PDPage page) throws Exception {
        return new PDPageContentStream(doc, page, AppendMode.APPEND, true, true);
    }

    private void applyAlpha(PDDocument doc, PDPageContentStream cs, double opacity) throws Exception {
        double a = Math.max(0, Math.min(1, opacity));
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant((float) a);
        gs.setStrokingAlphaConstant((float) a);
        cs.setGraphicsStateParameters(gs);
    }

    private void rect(PDPageContentStream cs, Transform tf,
                      double xMm, double yMm, double wMm, double hMm) throws Exception {
        float[] a = tf.pt(xMm, yMm);
        float[] b = tf.pt(xMm + wMm, yMm + hMm);
        float llx = Math.min(a[0], b[0]);
        float lly = Math.min(a[1], b[1]);
        float w = Math.abs(b[0] - a[0]);
        float hh = Math.abs(b[1] - a[1]);
        cs.addRect(llx, lly, w, hh);
    }

    private void ellipse(PDPageContentStream cs, Transform tf,
                         double xMm, double yMm, double wMm, double hMm) throws Exception {
        float[] a = tf.pt(xMm, yMm);
        float[] b = tf.pt(xMm + wMm, yMm + hMm);
        float cx = (a[0] + b[0]) / 2f;
        float cy = (a[1] + b[1]) / 2f;
        float rx = Math.abs(b[0] - a[0]) / 2f;
        float ry = Math.abs(b[1] - a[1]) / 2f;
        float kx = (float) (rx * KAPPA);
        float ky = (float) (ry * KAPPA);
        cs.moveTo(cx + rx, cy);
        cs.curveTo(cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry);
        cs.curveTo(cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy);
        cs.curveTo(cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry);
        cs.curveTo(cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy);
    }

    private void strokeLine(PDDocument doc, PDPage page, Transform tf,
                            double x1, double y1, double x2, double y2,
                            String color, float[] fallback, double lineWidthPt, double opacity)
            throws Exception {
        try (PDPageContentStream cs = open(doc, page)) {
            applyAlpha(doc, cs, opacity);
            float[] c = rgb(color, fallback);
            cs.setStrokingColor(c[0], c[1], c[2]);
            cs.setLineWidth((float) lineWidthPt);
            cs.setLineCapStyle(1);
            float[] p1 = tf.pt(x1, y1);
            float[] p2 = tf.pt(x2, y2);
            cs.moveTo(p1[0], p1[1]);
            cs.lineTo(p2[0], p2[1]);
            cs.stroke();
        }
    }

    private void drawArrow(PDDocument doc, PDPage page, Transform tf,
                           double[] startMm, double[] endMm,
                           String color, double lineWidthPt, double opacity) throws Exception {
        float[] s = tf.pt(startMm[0], startMm[1]);
        float[] e = tf.pt(endMm[0], endMm[1]);
        float[] c = rgb(color, new float[]{0f, 0f, 0f});
        double angle = Math.atan2(e[1] - s[1], e[0] - s[0]);
        double headLen = Math.max(6.0, lineWidthPt * 4.0);
        double headHalf = headLen * 0.45;
        double bx = e[0] - headLen * Math.cos(angle);
        double by = e[1] - headLen * Math.sin(angle);
        double leftX = bx + headHalf * Math.cos(angle + Math.PI / 2);
        double leftY = by + headHalf * Math.sin(angle + Math.PI / 2);
        double rightX = bx + headHalf * Math.cos(angle - Math.PI / 2);
        double rightY = by + headHalf * Math.sin(angle - Math.PI / 2);

        try (PDPageContentStream cs = open(doc, page)) {
            applyAlpha(doc, cs, opacity);
            cs.setStrokingColor(c[0], c[1], c[2]);
            cs.setNonStrokingColor(c[0], c[1], c[2]);
            cs.setLineWidth((float) lineWidthPt);
            cs.setLineCapStyle(1);
            cs.moveTo(s[0], s[1]);
            cs.lineTo((float) bx, (float) by);
            cs.stroke();
            cs.moveTo(e[0], e[1]);
            cs.lineTo((float) leftX, (float) leftY);
            cs.lineTo((float) rightX, (float) rightY);
            cs.closePath();
            cs.fill();
        }
    }

    // ==================== 文本辅助 ====================

    private List<String> wrapText(String text, org.apache.pdfbox.pdmodel.font.PDFont font,
                                  float fontPt, double maxWidthPt) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) { out.add(""); continue; }
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < paragraph.length(); i++) {
                char ch = paragraph.charAt(i);
                String trial = cur.toString() + ch;
                double width;
                try {
                    width = font.getStringWidth(safe(font, trial)) / 1000.0 * fontPt;
                } catch (Exception ex) {
                    width = 0;
                }
                if (maxWidthPt > 0 && width > maxWidthPt && cur.length() > 0) {
                    out.add(cur.toString());
                    cur = new StringBuilder();
                    cur.append(ch);
                } else {
                    cur.append(ch);
                }
            }
            out.add(cur.toString());
        }
        return out;
    }

    /** 去掉字体无法编码的字符，避免 showText 抛异常 */
    private String safe(org.apache.pdfbox.pdmodel.font.PDFont font, String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int cc = Character.charCount(cp);
            String piece = s.substring(i, i + cc);
            try {
                font.getStringWidth(piece);
                sb.append(piece);
            } catch (Exception ex) {
                sb.append(' ');
            }
            i += cc;
        }
        return sb.toString();
    }

    // ==================== 颜色/数值辅助 ====================

    private double nz(Double v) { return v != null ? v : 0.0; }

    private String firstNonNull(String a, String b) { return a != null ? a : b; }

    private boolean isTransparent(String color) {
        return color == null || "transparent".equalsIgnoreCase(color.trim());
    }

    /** 解析 #RRGGBB；transparent/空 返回 fallback（可为 null 表示不绘制） */
    private float[] rgb(String hex, float[] fallback) {
        if (isTransparent(hex)) return fallback;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            if (h.length() == 3) {
                int r = Integer.parseInt(h.substring(0, 1).repeat(2), 16);
                int g = Integer.parseInt(h.substring(1, 2).repeat(2), 16);
                int b = Integer.parseInt(h.substring(2, 3).repeat(2), 16);
                return new float[]{r / 255f, g / 255f, b / 255f};
            }
            if (h.length() >= 6) {
                int r = Integer.parseInt(h.substring(0, 2), 16);
                int g = Integer.parseInt(h.substring(2, 4), 16);
                int b = Integer.parseInt(h.substring(4, 6), 16);
                return new float[]{r / 255f, g / 255f, b / 255f};
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private double[][] parsePoints(String json) {
        if (json == null || json.isEmpty()) return new double[0][];
        try {
            return objectMapper.readValue(json, double[][].class);
        } catch (Exception e) {
            return new double[0][];
        }
    }

    private static int normalizeRotation(int rotation) {
        int r = rotation % 360;
        if (r < 0) r += 360;
        return r;
    }

    // ==================== 坐标变换 ====================

    /**
     * 可视坐标(mm/pt，原点左上、y 向下) → PDF 用户空间(pt，原点左下、y 向上) 的仿射变换。
     * user = A·v + b，A 为 2x2，b 为平移。images / text 复用同一 A。
     */
    static final class Transform {
        final double a11, a12, a21, a22, bx, by;
        final int rotation;

        private Transform(double a11, double a12, double a21, double a22,
                          double bx, double by, int rotation) {
            this.a11 = a11; this.a12 = a12; this.a21 = a21; this.a22 = a22;
            this.bx = bx; this.by = by; this.rotation = rotation;
        }

        static Transform of(PDPage page) {
            PDRectangle box = page.getCropBox();
            double Wu = box.getWidth();
            double Hu = box.getHeight();
            double ox = box.getLowerLeftX();
            double oy = box.getLowerLeftY();
            int r = normalizeRotation(page.getRotation());
            switch (r) {
                case 90:
                    return new Transform(0, 1, 1, 0, ox, oy, r);
                case 180:
                    return new Transform(-1, 0, 0, 1, ox + Wu, oy, r);
                case 270:
                    return new Transform(0, -1, -1, 0, ox + Wu, oy + Hu, r);
                case 0:
                default:
                    return new Transform(1, 0, 0, -1, ox, oy + Hu, r);
            }
        }

        /** 可视 mm 坐标 → 用户空间 pt */
        float[] pt(double xMm, double yMm) {
            double vx = xMm * MM_TO_PT;
            double vy = yMm * MM_TO_PT;
            double ux = a11 * vx + a12 * vy + bx;
            double uy = a21 * vx + a22 * vy + by;
            return new float[]{(float) ux, (float) uy};
        }

        /** 可视 pt 坐标 → 用户空间 pt */
        float[] ptFromPt(double vx, double vy) {
            double ux = a11 * vx + a12 * vy + bx;
            double uy = a21 * vx + a22 * vy + by;
            return new float[]{(float) ux, (float) uy};
        }

        /**
         * 用户空间 pt → 可视 pt（pt() 的逆）。
         * 四种旋转的 A 都是对合矩阵（A·A=I），故 v = A·(u - b)。
         */
        float[] invPt(double ux, double uy) {
            double dx = ux - bx;
            double dy = uy - by;
            double vx = a11 * dx + a12 * dy;
            double vy = a21 * dx + a22 * dy;
            return new float[]{(float) vx, (float) vy};
        }

        /** 绘制图片的矩阵：把 [0,1]^2 单位方块映射到「可视左上角(vx0,vy0)、尺寸(wv,hv) pt」的图片框 */
        Matrix imageMatrix(double vx0, double vy0, double wv, double hv) {
            float[] origin = ptFromPt(vx0, vy0 + hv); // 图片左下角对应可视左下角
            double a = wv * a11;
            double b = wv * a21;
            double c = -hv * a12;
            double d = -hv * a22;
            return new Matrix((float) a, (float) b, (float) c, (float) d, origin[0], origin[1]);
        }

        /** 文本矩阵：随页面旋转方向排版，平移到指定用户空间基线点 */
        Matrix textMatrix(float ex, float ey) {
            switch (rotation) {
                case 90:
                    return new Matrix(0, 1, -1, 0, ex, ey);
                case 180:
                    return new Matrix(-1, 0, 0, -1, ex, ey);
                case 270:
                    return new Matrix(0, -1, 1, 0, ex, ey);
                case 0:
                default:
                    return new Matrix(1, 0, 0, 1, ex, ey);
            }
        }
    }
}
