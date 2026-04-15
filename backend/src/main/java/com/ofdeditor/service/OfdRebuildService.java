package com.ofdeditor.service;

import com.ofdeditor.dto.ElementDTO;
import com.ofdeditor.dto.OfdDocumentDTO;
import com.ofdeditor.dto.PageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.ofdeditor.dto.AnnotationDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@Slf4j
@Service
public class OfdRebuildService {
    private final OfdCacheService cacheService;

    public OfdRebuildService(OfdCacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * 主入口
     * originalOfd != null → 精准修改XML模式（保留所有原始内容）
     * originalOfd == null → 降级重建模式（内容有损）
     */
    public byte[] rebuildOfd(OfdDocumentDTO documentDTO, byte[] originalOfd) throws Exception {
        if (originalOfd != null && originalOfd.length > 0) {
            log.info("使用精准修改模式重建OFD");
            return rebuildByPatchingXml(documentDTO, originalOfd);
        } else {
            log.warn("无原始OFD，降级为重建模式");
            return rebuildFromScratch(documentDTO);
        }
    }

    // =========================================================
    // 精准修改模式：解压 → 改XML → 重新打包
    // =========================================================

    private byte[] rebuildByPatchingXml(OfdDocumentDTO documentDTO, byte[] originalOfd) throws Exception {
        // 1. 收集所有被修改的元素（isDirty=true）
        Map<String, ElementDTO> dirtyElements = collectDirtyElements(documentDTO);
        log.info("共 {} 个元素被修改", dirtyElements.size());

        if (dirtyElements.isEmpty()) {
            log.info("无修改元素，直接返回原始OFD");
            return originalOfd;
        }

        // 2. 解压OFD到内存Map
        Map<String, byte[]> zipEntries = unzipToMap(originalOfd);

        // 3. 找到所有页面XML并修改
        patchPageXmls(zipEntries, documentDTO, dirtyElements);

        // 4. 重新打包成OFD字节
        byte[] result = zipFromMap(zipEntries);
        log.info("精准修改完成，大小: {} bytes", result.length);
        return result;
    }

    /**
     * 收集所有 isDirty=true 的元素，以 id 为 key
     */
    private Map<String, ElementDTO> collectDirtyElements(OfdDocumentDTO dto) {
        Map<String, ElementDTO> map = new LinkedHashMap<>();
        if (dto.getPages() == null) return map;
        for (PageDTO page : dto.getPages()) {
            if (page.getElements() == null) continue;
            for (ElementDTO el : page.getElements()) {
                if (el.getId() != null && Boolean.TRUE.equals(el.getIsDirty())) {
                    map.put(el.getId(), el);
                }
            }
        }
        return map;
    }

    /**
     * 遍历ZIP中所有页面XML，对每个dirty元素做坐标/内容匹配后修改
     */
    private void patchPageXmls(Map<String, byte[]> zipEntries,
                               OfdDocumentDTO documentDTO,
                               Map<String, ElementDTO> dirtyElements) throws Exception {

        // 按页构建 dirty 元素列表，方便后续按页匹配
        Map<Integer, List<ElementDTO>> dirtyByPage = new LinkedHashMap<>();
        if (documentDTO.getPages() != null) {
            for (PageDTO page : documentDTO.getPages()) {
                List<ElementDTO> dirtyOnPage = new ArrayList<>();
                if (page.getElements() != null) {
                    for (ElementDTO el : page.getElements()) {
                        if (Boolean.TRUE.equals(el.getIsDirty())) {
                            dirtyOnPage.add(el);
                        }
                    }
                }
                if (!dirtyOnPage.isEmpty()) {
                    dirtyByPage.put(page.getPageIndex(), dirtyOnPage);
                }
            }
        }

        // 找所有页面Content.xml路径（兼容各种OFD结构）
        List<String> pageXmlPaths = findPageContentXmlPaths(zipEntries);
        log.info("找到 {} 个页面XML", pageXmlPaths.size());

        for (int pageIdx = 0; pageIdx < pageXmlPaths.size(); pageIdx++) {
            String xmlPath = pageXmlPaths.get(pageIdx);
            List<ElementDTO> dirtyOnThisPage = dirtyByPage.get(pageIdx);

            if (dirtyOnThisPage == null || dirtyOnThisPage.isEmpty()) {
                log.debug("第{}页无修改，跳过", pageIdx + 1);
                continue;
            }

            byte[] xmlBytes = zipEntries.get(xmlPath);
            if (xmlBytes == null) continue;

            byte[] patchedXml = patchOnePageXml(xmlBytes, dirtyOnThisPage);
            zipEntries.put(xmlPath, patchedXml);
            log.info("第{}页XML已修改: {}", pageIdx + 1, xmlPath);
        }
    }

    /**
     * 找所有页面Content.xml的ZIP路径，按页序排序
     */
    private List<String> findPageContentXmlPaths(Map<String, byte[]> zipEntries) {
        List<String> result = new ArrayList<>();

        // 标准结构: Doc_0/Pages/Page_0/Content.xml
        for (String key : zipEntries.keySet()) {
            if (key.matches(".*/Pages/Page_\\d+/Content\\.xml")) {
                result.add(key);
            }
        }

        // 按页号数字排序
        result.sort((a, b) -> {
            int na = extractPageNumber(a);
            int nb = extractPageNumber(b);
            return Integer.compare(na, nb);
        });

        // 如果标准结构找不到，尝试其他命名
        if (result.isEmpty()) {
            for (String key : zipEntries.keySet()) {
                String lower = key.toLowerCase();
                if (lower.contains("page") && lower.endsWith("content.xml")) {
                    result.add(key);
                }
            }
            result.sort(Comparator.naturalOrder());
        }

        return result;
    }

    private int extractPageNumber(String path) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("Page_(\\d+)").matcher(path);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 0;
    }

    /**
     * 修改单页XML：找到匹配的TextObject节点，更新内容
     */
    private byte[] patchOnePageXml(byte[] xmlBytes, List<ElementDTO> dirtyElements) throws Exception {
        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();

        for (ElementDTO el : dirtyElements) {
            try {
                patchElement(doc, root, el);
            } catch (Exception e) {
                log.warn("修改元素失败 id={}: {}", el.getId(), e.getMessage());
            }
        }

        return serializeXml(doc);
    }

    /**
     * 在页面XML中找到对应节点并修改
     * 匹配策略：优先用 originalX/Y/Width/Height 坐标匹配，精度 0.5mm
     */
    private void patchElement(Document doc, Element root, ElementDTO el) {
        String type = el.getType();
        if (type == null) return;

        switch (type) {
            case "TEXT" -> patchTextElement(doc, root, el);
            case "IMAGE" -> patchImageElement(doc, root, el);
            case "PATH" -> patchPathElement(doc, root, el);
        }
    }

    private void patchTextElement(Document doc, Element root, ElementDTO el) {
        // 找所有 TextObject 节点
        NodeList nodes = getElementsByLocalName(root, "TextObject");
        Element matched = findBestMatchByBoundary(nodes, el);

        if (matched == null) {
            log.warn("TEXT元素未找到匹配节点: id={}, x={}, y={}", el.getId(), el.getOriginalX(), el.getOriginalY());
            return;
        }

        // 1. 修改 Boundary（位置/尺寸）
        if (isPositionChanged(el)) {
            updateBoundaryAttr(matched, el);
        }

        // 2. 修改文本内容
        if (el.getContent() != null) {
            updateTextContent(doc, matched, el.getContent());
        }

        // 3. 修改字体大小
        if (el.getFontSize() != null) {
            updateFontSize(matched, el.getFontSize());
        }

        // 4. 修改颜色
        if (el.getColor() != null) {
            updateTextColor(doc, matched, el.getColor());
        }
        // 5. 修改斜体
        if (el.getItalic() != null) {
            updateTextItalic(doc, matched, el.getItalic());
        }
        // 6. 修改旋转角度
        if (el.getRotation() != null) {
            updateTextRotation(matched, el.getRotation());
        }

        log.debug("TEXT节点已修改: content={}", el.getContent());
    }

    private void patchImageElement(Document doc, Element root, ElementDTO el) {
        NodeList nodes = getElementsByLocalName(root, "ImageObject");
        Element matched = findBestMatchByBoundary(nodes, el);
        if (matched == null) {
            log.warn("IMAGE元素未找到匹配节点: id={}", el.getId());
            return;
        }
        if (isPositionChanged(el)) {
            updateBoundaryAttr(matched, el);
        }
        log.debug("IMAGE节点位置已修改");
    }

    private void patchPathElement(Document doc, Element root, ElementDTO el) {
        NodeList nodes = getElementsByLocalName(root, "PathObject");
        Element matched = findBestMatchByBoundary(nodes, el);
        if (matched == null) {
            log.warn("PATH元素未找到匹配节点: id={}", el.getId());
            return;
        }
        if (isPositionChanged(el)) {
            updateBoundaryAttr(matched, el);
        }
        log.debug("PATH节点位置已修改");
    }

    // ---- 匹配逻辑 ----

    /**
     * 用原始坐标 (originalX, originalY, originalWidth, originalHeight) 在XML节点中找最佳匹配
     * 容差：0.5mm
     */
    private Element findBestMatchByBoundary(NodeList nodes, ElementDTO el) {
        double ox = el.getOriginalX() != null ? el.getOriginalX() :
                (el.getX() != null ? el.getX() : 0);
        double oy = el.getOriginalY() != null ? el.getOriginalY() :
                (el.getY() != null ? el.getY() : 0);
        double ow = el.getOriginalWidth() != null ? el.getOriginalWidth() :
                (el.getWidth() != null ? el.getWidth() : 0);
        double oh = el.getOriginalHeight() != null ? el.getOriginalHeight() :
                (el.getHeight() != null ? el.getHeight() : 0);

        Element bestMatch = null;
        double bestScore = Double.MAX_VALUE;

        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;

            double[] boundary = parseBoundaryForMatch(e);
            if (boundary == null) continue;

            double score = Math.abs(boundary[0] - ox)
                    + Math.abs(boundary[1] - oy)
                    + Math.abs(boundary[2] - ow)
                    + Math.abs(boundary[3] - oh);

            if (score < bestScore) {
                bestScore = score;
                bestMatch = e;
            }
        }

        // 容差 2.0（4个维度各0.5mm）
        if (bestScore > 2.0) {
            log.debug("最佳匹配分数过大: score={}, 放弃匹配", bestScore);
            return null;
        }

        return bestMatch;
    }

    /**
     * 解析 Boundary 属性，格式："x y w h"
     */
    private double[] parseBoundaryAttr(Element e) {
        String boundary = getAttrIgnoreNs(e, "Boundary");
        if (boundary == null || boundary.isBlank()) return null;

        String[] parts = boundary.trim().split("\\s+");
        if (parts.length < 4) return null;

        try {
            return new double[]{
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3])
            };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 匹配时优先使用“应用 CTM 后的可视边界”，这样和前端编辑坐标系一致。
     */
    private double[] parseBoundaryForMatch(Element e) {
        double[] raw = parseBoundaryAttr(e);
        if (raw == null) return null;
        String ctm = getAttrIgnoreNs(e, "CTM");
        if (ctm == null || ctm.isBlank()) return raw;

        List<Double> nums = extractNumbers(ctm);
        if (nums.size() < 6) return raw;
        double a = nums.get(0), b = nums.get(1), c = nums.get(2);
        double d = nums.get(3), tx = nums.get(4), ty = nums.get(5);

        double x = raw[0], y = raw[1], w = raw[2], h = raw[3];
        double[] p1 = applyCtm(a, b, c, d, tx, ty, x, y);
        double[] p2 = applyCtm(a, b, c, d, tx, ty, x + w, y);
        double[] p3 = applyCtm(a, b, c, d, tx, ty, x, y + h);
        double[] p4 = applyCtm(a, b, c, d, tx, ty, x + w, y + h);

        double minX = Math.min(Math.min(p1[0], p2[0]), Math.min(p3[0], p4[0]));
        double minY = Math.min(Math.min(p1[1], p2[1]), Math.min(p3[1], p4[1]));
        double maxX = Math.max(Math.max(p1[0], p2[0]), Math.max(p3[0], p4[0]));
        double maxY = Math.max(Math.max(p1[1], p2[1]), Math.max(p3[1], p4[1]));
        return new double[]{minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY)};
    }

    private double[] applyCtm(double a, double b, double c, double d, double tx, double ty, double x, double y) {
        return new double[]{a * x + c * y + tx, b * x + d * y + ty};
    }

    // ---- 修改逻辑 ----

    private boolean isPositionChanged(ElementDTO el) {
        if (el.getX() == null || el.getY() == null) return false;
        double dx = Math.abs(nvl(el.getX()) - nvl(el.getOriginalX()));
        double dy = Math.abs(nvl(el.getY()) - nvl(el.getOriginalY()));
        double dw = Math.abs(nvl(el.getWidth()) - nvl(el.getOriginalWidth()));
        double dh = Math.abs(nvl(el.getHeight()) - nvl(el.getOriginalHeight()));
        return dx + dy + dw + dh > 0.001;
    }

    private void updateBoundaryAttr(Element node, ElementDTO el) {
        String newBoundary = String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f",
                el.getX(), el.getY(),
                nvl(el.getWidth()), nvl(el.getHeight()));
        setAttrIgnoreNs(node, "Boundary", newBoundary);
        log.debug("更新Boundary: {}", newBoundary);
    }

    private void updateTextContent(Document doc, Element textObj, String newContent) {
        // 找所有 TextCode 节点
        NodeList textCodes = getElementsByLocalName(textObj, "TextCode");
        if (textCodes.getLength() == 0) {
            log.warn("TextObject 中未找到 TextCode 节点");
            return;
        }

        if (textCodes.getLength() == 1) {
            // 单个 TextCode：直接替换内容
            textCodes.item(0).setTextContent(newContent);
        } else {
            // 多个 TextCode（可能是逐字定位）：
            // 策略：把所有内容合并到第一个，删除其余
            textCodes.item(0).setTextContent(newContent);
            for (int i = textCodes.getLength() - 1; i >= 1; i--) {
                Node tc = textCodes.item(i);
                tc.getParentNode().removeChild(tc);
            }
        }
    }

    private void updateFontSize(Element textObj, double fontSize) {
        // TextObject 的 Size 属性
        setAttrIgnoreNs(textObj, "Size", String.format(Locale.ROOT, "%.3f", fontSize));
    }

    private void updateTextColor(Document doc, Element textObj, String hexColor) {
        try {
            int[] rgb = hexToRgb(hexColor);
            if (rgb == null) return;

            // 找 FillColor 节点
            NodeList fillColors = getElementsByLocalName(textObj, "FillColor");
            if (fillColors.getLength() > 0) {
                Element fc = (Element) fillColors.item(0);
                setAttrIgnoreNs(fc, "Value",
                        rgb[0] + " " + rgb[1] + " " + rgb[2]);
            } else {
                Element fc = doc.createElement("FillColor");
                setAttrIgnoreNs(fc, "Value",
                        rgb[0] + " " + rgb[1] + " " + rgb[2]);
                textObj.appendChild(fc);
            }
            // 找 DefaultFillColor（部分OFD）
            NodeList defaultFills = getElementsByLocalName(textObj, "DefaultFillColor");
            if (defaultFills.getLength() > 0) {
                Element fc = (Element) defaultFills.item(0);
                setAttrIgnoreNs(fc, "Value",
                        rgb[0] + " " + rgb[1] + " " + rgb[2]);
            }
            // 找 DefaultAppearance/FillColor（部分OFD）
            NodeList daNodes = getElementsByLocalName(textObj, "DefaultAppearance");
            if (daNodes.getLength() > 0 && daNodes.item(0) instanceof Element daEl) {
                NodeList daFills = getElementsByLocalName(daEl, "FillColor");
                if (daFills.getLength() > 0) {
                    Element fc = (Element) daFills.item(0);
                    setAttrIgnoreNs(fc, "Value",
                            rgb[0] + " " + rgb[1] + " " + rgb[2]);
                } else {
                    Element fc = doc.createElement("FillColor");
                    setAttrIgnoreNs(fc, "Value",
                            rgb[0] + " " + rgb[1] + " " + rgb[2]);
                    daEl.appendChild(fc);
                }
            }
        } catch (Exception e) {
            log.warn("更新颜色失败: {}", e.getMessage());
        }
    }

    private void updateTextItalic(Document doc, Element textObj, boolean italic) {
        String value = italic ? "true" : "false";
        setAttrIgnoreNs(textObj, "Italic", value);
        NodeList fontNodes = getElementsByLocalName(textObj, "Font");
        if (fontNodes.getLength() > 0) {
            for (int i = 0; i < fontNodes.getLength(); i++) {
                if (fontNodes.item(i) instanceof Element fontEl) {
                    setAttrIgnoreNs(fontEl, "Italic", value);
                }
            }
        } else {
            Element fontEl = doc.createElement("Font");
            setAttrIgnoreNs(fontEl, "Italic", value);
            textObj.appendChild(fontEl);
        }
    }

    private void updateTextRotation(Element textObj, double rotation) {
        setAttrIgnoreNs(textObj, "Rotate", String.format(Locale.ROOT, "%.3f", rotation));
    }

    private int[] hexToRgb(String hex) {
        if (hex == null) return null;
        String h = hex.replace("#", "").trim();
        if (h.length() != 6) return null;
        try {
            return new int[]{
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16)
            };
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    // ZIP 操作
    // =========================================================

    private Map<String, byte[]> unzipToMap(byte[] ofdBytes) throws Exception {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(ofdBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    map.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        log.info("解压OFD完成，共 {} 个文件", map.size());
        return map;
    }

    private byte[] zipFromMap(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.setMethod(ZipOutputStream.DEFLATED);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    // =========================================================
    // XML 工具
    // =========================================================

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private byte[] serializeXml(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        // 保留XML声明
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc),
                new StreamResult(new OutputStreamWriter(baos, StandardCharsets.UTF_8)));
        return baos.toByteArray();
    }

    /**
     * 忽略命名空间查找子元素
     */
    private NodeList getElementsByLocalName(Element root, String localName) {
        NodeList ns = root.getElementsByTagNameNS("*", localName);
        if (ns.getLength() > 0) return ns;
        return root.getElementsByTagName(localName);
    }

    /**
     * 忽略命名空间获取属性
     */
    private String getAttrIgnoreNs(Element e, String attrName) {
        String v = e.getAttribute(attrName);
        if (v != null && !v.isEmpty()) return v;
        // 尝试带命名空间前缀
        for (String prefix : new String[]{"ofd:", "ofd2:", "doc:", ""}) {
            v = e.getAttribute(prefix + attrName);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * 忽略命名空间设置属性（保持原有命名空间前缀）
     */
    private void setAttrIgnoreNs(Element e, String attrName, String value) {
        // 先尝试找已有属性名（含前缀）
        for (String prefix : new String[]{"", "ofd:", "ofd2:", "doc:"}) {
            String full = prefix + attrName;
            if (e.hasAttribute(full)) {
                e.setAttribute(full, value);
                return;
            }
        }
        // 没有则直接设置
        e.setAttribute(attrName, value);
    }

    private double nvl(Double v) {
        return v == null ? 0.0 : v;
    }

    private List<Double> extractNumbers(String s) {
        List<Double> result = new ArrayList<>();
        if (s == null) return result;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("-?\\d+\\.?\\d*").matcher(s);
        while (matcher.find()) {
            try {
                result.add(Double.parseDouble(matcher.group()));
            } catch (Exception ignore) {
            }
        }
        return result;
    }

    // =========================================================
    // 降级模式（无原始OFD时使用，仅输出文字）
    // =========================================================

    private byte[] rebuildFromScratch(OfdDocumentDTO documentDTO) throws Exception {
        // 保留原来的 ofdrw 重建逻辑作为兜底
        Path tempOutput = Files.createTempFile("rebuilt_", ".ofd");
        try {
            try (org.ofdrw.layout.OFDDoc ofdDoc = new org.ofdrw.layout.OFDDoc(tempOutput)) {
                for (PageDTO pageDTO : documentDTO.getPages()) {
                    org.ofdrw.layout.VirtualPage vPage = new org.ofdrw.layout.VirtualPage(
                            new org.ofdrw.layout.PageLayout(pageDTO.getWidth(), pageDTO.getHeight())
                    );
                    if (pageDTO.getElements() != null) {
                        for (ElementDTO element : pageDTO.getElements()) {
                            try {
                                addTextElementFallback(vPage, element);
                            } catch (Exception e) {
                                log.warn("降级模式添加元素失败: {}", e.getMessage());
                            }
                        }
                    }
                    ofdDoc.addVPage(vPage);
                }
            }
            return Files.readAllBytes(tempOutput);
        } finally {
            Files.deleteIfExists(tempOutput);
        }
    }
    // =========================================================
// 工具方法（OfdRebuildService 内部使用）
// =========================================================

    /** 判断字符串非空 */
    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

// =========================================================
// 注释层专用重建方法（配合 AnnotationService 使用）
// =========================================================

    /**
     * 从原始 OFD 字节 + 全量注释数据重建 OFD（用于 exportWithAnnotations）
     */
    public byte[] rebuildWithAnnotations(byte[] originalOfd,
                                         Map<Integer, List<AnnotationDTO>> allAnnotations)
            throws Exception {
        if (originalOfd == null || originalOfd.length == 0) {
            throw new IllegalArgumentException("原始OFD为空");
        }
        if (allAnnotations == null || allAnnotations.isEmpty()) {
            return originalOfd;
        }

        log.info("开始重建含注释的OFD，共 {} 页有注释", allAnnotations.size());

        Map<String, byte[]> zipEntries = unzipToMap(originalOfd);

        for (Map.Entry<Integer, List<AnnotationDTO>> entry : allAnnotations.entrySet()) {
            int pageIndex = entry.getKey();
            List<AnnotationDTO> annotations = entry.getValue();
            if (annotations == null || annotations.isEmpty()) continue;

            String annotPath = findAnnotationXmlPath(zipEntries, pageIndex);
            if (annotPath == null) {
                annotPath = String.format("Doc_0/Annots/Page_%d/Annotation.xml", pageIndex);
            }

            byte[] xmlBytes = zipEntries.containsKey(annotPath)
                    ? zipEntries.get(annotPath)
                    : createEmptyAnnotationXml();

            byte[] patchedXml = patchAnnotationXml(xmlBytes, annotations);
            zipEntries.put(annotPath, patchedXml);
            log.debug("写入注释: page={}, path={}, count={}", pageIndex, annotPath, annotations.size());
        }

        byte[] result = zipFromMap(zipEntries);
        log.info("含注释OFD重建完成，大小: {} bytes", result.length);
        return result;
    }

    /**
     * 新增一条注释，更新缓存中的 OFD 字节
     */
    public void writeAnnotationToOfd(String fileId, AnnotationDTO annotation) throws Exception {
        byte[] originalOfd = cacheService.get(fileId);
        if (originalOfd == null) {
            throw new IllegalStateException("缓存中找不到文件: " + fileId);
        }

        Map<String, byte[]> zipEntries = unzipToMap(originalOfd);
        int pageIndex = annotation.getPageIndex() != null ? annotation.getPageIndex() : 0;

        String annotPath = findAnnotationXmlPath(zipEntries, pageIndex);
        if (annotPath == null) {
            annotPath = String.format("Doc_0/Annots/Page_%d/Annotation.xml", pageIndex);
        }

        byte[] xmlBytes = zipEntries.containsKey(annotPath)
                ? zipEntries.get(annotPath)
                : createEmptyAnnotationXml();

        // 追加该条注释
        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();
        Element annEl = createAnnotationElement(doc, annotation);
        if (annEl != null) {
            root.appendChild(annEl);
        }

        zipEntries.put(annotPath, serializeXml(doc));
        cacheService.put(fileId, zipFromMap(zipEntries));

        log.debug("writeAnnotationToOfd: fileId={}, type={}, page={}",
                fileId, annotation.getType(), pageIndex);
    }

    /**
     * 更新一条注释（先按坐标匹配删除旧节点，再追加新节点）
     */
    public void updateAnnotationInOfd(String fileId, AnnotationDTO oldAnnotation, AnnotationDTO updated) throws Exception {
        byte[] originalOfd = cacheService.get(fileId);
        if (originalOfd == null) {
            throw new IllegalStateException("缓存中找不到文件: " + fileId);
        }

        Map<String, byte[]> zipEntries = unzipToMap(originalOfd);
        int pageIndex = updated.getPageIndex() != null ? updated.getPageIndex() : 0;

        String annotPath = findAnnotationXmlPath(zipEntries, pageIndex);
        if (annotPath == null) {
            // 该页暂无注释层，直接当新增处理
            writeAnnotationToOfd(fileId, updated);
            return;
        }

        byte[] xmlBytes = zipEntries.get(annotPath);
        if (xmlBytes == null) {
            writeAnnotationToOfd(fileId, updated);
            return;
        }

        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();

        // 按旧坐标近似匹配删除旧节点，避免先更新坐标后删不到原节点
        AnnotationDTO removeTarget = oldAnnotation != null ? oldAnnotation : updated;
        removeNodeByPosition(root, removeTarget.getX(), removeTarget.getY(),
                removeTarget.getWidth(), removeTarget.getHeight());

        // 追加新节点
        Element annEl = createAnnotationElement(doc, updated);
        if (annEl != null) {
            root.appendChild(annEl);
        }

        zipEntries.put(annotPath, serializeXml(doc));
        cacheService.put(fileId, zipFromMap(zipEntries));
        log.debug("updateAnnotationInOfd: fileId={}, id={}", fileId, updated.getId());
    }

    /**
     * 删除单条注释（只有 annotationId，无法精确匹配 OFD 节点，此处仅更新缓存 OFD 中对应页的内容）
     * 实际策略：由于 OFD 注释节点没有 ID 属性，删除时依赖 AnnotationService 传入坐标信息。
     * 本方法作为"重新全量写入该页注释"的触发入口，坐标匹配删除由 removeNodeByPosition 完成。
     * 注意：调用方 AnnotationService 在缓存中删除后，应调用 rebuildWithAnnotations 全量刷新，
     * 因此这里采用"重新序列化整页注释"的轻量实现。
     */
    public void removeAnnotationFromOfd(String fileId, String annotationId) throws Exception {
        // 单条删除时我们无法从 annotationId 反推坐标，
        // 因此只记录日志，实际 OFD 在 export 时由 rebuildWithAnnotations 全量同步。
        // 如需实时同步，请改用 rebuildWithAnnotations 全量模式。
        log.debug("removeAnnotationFromOfd: fileId={}, annotationId={} (延迟同步至export)",
                fileId, annotationId);
    }

    /**
     * 删除某页所有注释
     */
    public void removeAllAnnotationsFromOfd(String fileId, Integer pageIndex) throws Exception {
        byte[] originalOfd = cacheService.get(fileId);
        if (originalOfd == null) {
            log.warn("removeAllAnnotationsFromOfd: 缓存找不到 fileId={}", fileId);
            return;
        }

        Map<String, byte[]> zipEntries = unzipToMap(originalOfd);

        int pi = pageIndex != null ? pageIndex : 0;
        String annotPath = findAnnotationXmlPath(zipEntries, pi);
        if (annotPath == null) {
            log.debug("第 {} 页无注释层，跳过", pi);
            return;
        }

        byte[] xmlBytes = zipEntries.get(annotPath);
        if (xmlBytes == null) return;

        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();

        // 清空所有子节点（保留根节点和命名空间）
        NodeList children = root.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                root.removeChild(child);
            }
        }

        zipEntries.put(annotPath, serializeXml(doc));
        cacheService.put(fileId, zipFromMap(zipEntries));
        log.debug("清空第 {} 页注释层: {}", pi, annotPath);
    }

// =========================================================
// 注释层辅助方法
// =========================================================

    private String findAnnotationXmlPath(Map<String, byte[]> zipEntries, int pageIndex) {
        String[] candidates = {
                String.format("Doc_0/Annots/Page_%d/Annotation.xml",   pageIndex),
                String.format("doc_0/Annots/Page_%d/Annotation.xml",   pageIndex),
                String.format("Doc_0/Annots/Page_%04d/Annotation.xml", pageIndex),
                String.format("doc_0/Annots/Page_%04d/Annotation.xml", pageIndex)
        };
        for (String c : candidates) {
            if (zipEntries.containsKey(c)) return c;
        }
        // 模糊匹配
        for (String key : zipEntries.keySet()) {
            if (key.contains("Page_" + pageIndex) && key.endsWith("Annotation.xml")) {
                return key;
            }
        }
        return null;
    }

    private byte[] createEmptyAnnotationXml() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ofd:Page xmlns:ofd=\"http://www.ofdspec.org/2016\"></ofd:Page>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] patchAnnotationXml(byte[] xmlBytes, List<AnnotationDTO> annotations) throws Exception {
        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();
        for (AnnotationDTO ann : annotations) {
            Element el = createAnnotationElement(doc, ann);
            if (el != null) root.appendChild(el);
        }
        return serializeXml(doc);
    }

    private Element createAnnotationElement(Document doc, AnnotationDTO ann) {
        if (ann == null || ann.getType() == null) return null;
        return switch (ann.getType()) {
            case "STAMP"                                                         -> createStampElement(doc, ann);
            case "TEXTBOX", "STICKYNOTE"                                         -> createTextElement(doc, ann);
            case "HIGHLIGHT", "UNDERLINE", "STRIKEOUT",
                 "RECTANGLE", "CIRCLE", "ARROW", "FREEHAND"                     -> createPathElement(doc, ann);
            default -> {
                log.warn("未知注释类型: {}，使用 PATH 兜底", ann.getType());
                yield createPathElement(doc, ann);
            }
        };
    }

    private Element createStampElement(Document doc, AnnotationDTO ann) {
        Element el = doc.createElement("ImageObject");
        setBoundaryAttr(el, ann);
        // AnnotationDTO 无 resourceId 字段，stampBase64 存入自定义属性
        if (isNotBlank(ann.getStampBase64())) {
            // 不直接嵌入 Base64（会使文件过大），记录占位属性即可
            el.setAttribute("StampType", "base64");
        }
        return el;
    }

    private Element createPathElement(Document doc, AnnotationDTO ann) {
        Element el = doc.createElement("PathObject");
        setBoundaryAttr(el, ann);
        if (isNotBlank(ann.getType())) {
            el.setAttribute("Type", ann.getType());
        }

        if (ann.getLineWidth() != null && ann.getLineWidth() > 0) {
            el.setAttribute("LineWidth",
                    String.format(Locale.ROOT, "%.3f", ann.getLineWidth()));
        }

        if (isNotBlank(ann.getStrokeColor())) {
            Element strokeEl = doc.createElement("StrokeColor");
            strokeEl.setAttribute("Value", hexToRgbSpaced(ann.getStrokeColor()));
            el.appendChild(strokeEl);
        }

        if (isNotBlank(ann.getColor())) {
            Element fillEl = doc.createElement("FillColor");
            fillEl.setAttribute("Value", hexToRgbSpaced(ann.getColor()));
            el.appendChild(fillEl);
        }

        if (isNotBlank(ann.getPathPoints())) {
            Element abbrev = doc.createElement("AbbreviatedData");
            String ofdPath = annotationPathPointsToOfdPath(ann.getPathPoints(), ann.getType());
            abbrev.setTextContent(isNotBlank(ofdPath) ? ofdPath : ann.getPathPoints());
            el.appendChild(abbrev);
            el.setAttribute("PathPoints", ann.getPathPoints());
        }

        return el;
    }

    private Element createTextElement(Document doc, AnnotationDTO ann) {
        Element el = doc.createElement("TextObject");
        setBoundaryAttr(el, ann);

        if (ann.getFontSize() != null) {
            el.setAttribute("Size", String.format(Locale.ROOT, "%.3f", ann.getFontSize()));
        }

        if (isNotBlank(ann.getFontColor())) {
            Element fillEl = doc.createElement("FillColor");
            fillEl.setAttribute("Value", hexToRgbSpaced(ann.getFontColor()));
            el.appendChild(fillEl);
        }

        Element textCode = doc.createElement("TextCode");
        textCode.setTextContent(ann.getContent() != null ? ann.getContent() : "");
        el.appendChild(textCode);

        return el;
    }

    private void setBoundaryAttr(Element e, AnnotationDTO ann) {
        double x = ann.getX()      != null ? ann.getX()      : 0.0;
        double y = ann.getY()      != null ? ann.getY()      : 0.0;
        double w = ann.getWidth()  != null ? ann.getWidth()  : 0.0;
        double h = ann.getHeight() != null ? ann.getHeight() : 0.0;
        e.setAttribute("Boundary",
                String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f", x, y, w, h));
    }

    /**
     * 按位置近似匹配并删除 XML 子节点
     * 容差：各维度 0.5mm，总分 < 2.0
     */
    private void removeNodeByPosition(Element root,
                                      Double x, Double y, Double w, Double h) {
        if (x == null || y == null) return;
        NodeList children = root.getChildNodes();
        Node toRemove = null;
        double bestScore = 2.0; // 容差阈值

        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (!(n instanceof Element el)) continue;
            double[] bound = parseBoundaryAttr(el);
            if (bound == null) continue;

            double score = Math.abs(bound[0] - x)
                    + Math.abs(bound[1] - y)
                    + Math.abs(bound[2] - (w != null ? w : 0.0))
                    + Math.abs(bound[3] - (h != null ? h : 0.0));
            if (score < bestScore) {
                bestScore = score;
                toRemove = n;
            }
        }

        if (toRemove != null) {
            root.removeChild(toRemove);
            log.debug("removeNodeByPosition: 删除节点, score={}", bestScore);
        }
    }

    /** #RRGGBB → "R G B" 空格分隔 */
    private String hexToRgbSpaced(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) return "0 0 0";
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return r + " " + g + " " + b;
        } catch (Exception e) {
            return "0 0 0";
        }
    }

    private String annotationPathPointsToOfdPath(String pathPointsJson, String annType) {
        if (!isNotBlank(pathPointsJson)) return null;
        List<double[]> points = parsePointPairs(pathPointsJson);
        if (points.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        double[] first = points.get(0);
        sb.append(String.format(Locale.ROOT, "M %.3f %.3f", first[0], first[1]));
        for (int i = 1; i < points.size(); i++) {
            double[] p = points.get(i);
            sb.append(String.format(Locale.ROOT, " L %.3f %.3f", p[0], p[1]));
        }
        if ("RECTANGLE".equals(annType) || "CIRCLE".equals(annType)) {
            sb.append(" C");
        }
        return sb.toString();
    }

    private List<double[]> parsePointPairs(String json) {
        List<double[]> out = new ArrayList<>();
        if (!isNotBlank(json)) return out;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*\\]")
                    .matcher(json);
            while (m.find()) {
                out.add(new double[]{
                        Double.parseDouble(m.group(1)),
                        Double.parseDouble(m.group(2))
                });
            }
        } catch (Exception ignore) {
        }
        return out;
    }
    private void addTextElementFallback(org.ofdrw.layout.VirtualPage vPage, ElementDTO element) {
        if (element == null || !"TEXT".equals(element.getType())) return;
        if (element.getContent() == null || element.getContent().isEmpty()) return;

        org.ofdrw.layout.element.Paragraph p = new org.ofdrw.layout.element.Paragraph("");
        p.setPosition(org.ofdrw.layout.element.Position.Absolute);
        if (element.getX() != null) p.setX(element.getX());
        if (element.getY() != null) p.setY(element.getY());
        if (element.getWidth() != null) p.setWidth(element.getWidth());
        if (element.getHeight() != null) p.setHeight(element.getHeight());

        org.ofdrw.layout.element.Span span =
                new org.ofdrw.layout.element.Span(element.getContent());
        if (element.getFontSize() != null) span.setFontSize(element.getFontSize());
        p.add(span);
        vPage.add(p);
    }
}