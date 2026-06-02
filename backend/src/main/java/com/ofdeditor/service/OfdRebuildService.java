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
        Map<String, ElementDTO> dirtyElements = collectDirtyElements(documentDTO);
        log.info("共 {} 个元素被修改", dirtyElements.size());

        Map<String, byte[]> zipEntries = unzipToMap(originalOfd);
        List<String> originalPagePaths = findPageContentXmlPaths(zipEntries);

        boolean structureChanged = needsStructuralRebuild(documentDTO, originalPagePaths);
        if (structureChanged) {
            log.info("检测到页面结构变更（重排/复制/增删），重建页面结构");
            rebuildPageStructure(zipEntries, documentDTO, originalPagePaths);
        }

        if (dirtyElements.isEmpty() && !structureChanged) {
            log.info("无修改，直接返回原始OFD");
            return originalOfd;
        }

        if (!dirtyElements.isEmpty()) {
            String docPrefix = extractDocPrefix(findPageContentXmlPaths(zipEntries));
            patchPageXmls(zipEntries, documentDTO, dirtyElements, docPrefix);
        }

        byte[] result = zipFromMap(zipEntries);
        log.info("精准修改完成，大小: {} bytes", result.length);
        return result;
    }

    /**
     * 判断 DTO 页序/页数是否与原始 OFD 一致
     */
    private boolean needsStructuralRebuild(OfdDocumentDTO dto, List<String> originalPagePaths) {
        if (dto.getPages() == null) return false;
        int dtoCount = dto.getPages().size();
        if (dtoCount != originalPagePaths.size()) return true;
        for (int i = 0; i < dtoCount; i++) {
            int src = resolveSourcePageIndex(dto.getPages().get(i), i);
            if (src != i) return true;
        }
        return false;
    }

    private int resolveSourcePageIndex(PageDTO page, int defaultIndex) {
        if (page == null) return defaultIndex;
        if (page.getSourcePageIndex() != null) return page.getSourcePageIndex();
        return defaultIndex;
    }

    /**
     * 按 DTO 页序重建 Pages 目录并更新 Document.xml
     */
    private void rebuildPageStructure(Map<String, byte[]> zipEntries,
                                      OfdDocumentDTO dto,
                                      List<String> originalPagePaths) throws Exception {
        String docPrefix = extractDocPrefix(originalPagePaths);
        String docXmlPath = findDocumentXmlPath(zipEntries, docPrefix);

        // 备份原始页面目录内容（即将从 zip 中删除）
        Map<Integer, Map<String, byte[]>> originalPageDirs = snapshotPageDirectories(zipEntries, originalPagePaths);

        // 删除旧 Pages 目录
        String pagesPrefix = docPrefix + "/Pages/";
        zipEntries.keySet().removeIf(k -> k.replace('\\', '/').startsWith(pagesPrefix));

        List<PageDTO> pages = dto.getPages();
        for (int i = 0; i < pages.size(); i++) {
            PageDTO page = pages.get(i);
            int srcIdx = resolveSourcePageIndex(page, i);
            boolean isBlank = isBlankInsertedPage(page);

            String destDir = docPrefix + "/Pages/Page_" + i + "/";
            String destContent = destDir + "Content.xml";

            if (isBlank) {
                zipEntries.put(destContent, createBlankPageContentXml(page));
            } else if (srcIdx >= 0 && srcIdx < originalPagePaths.size()) {
                Map<String, byte[]> srcDir = originalPageDirs.get(srcIdx);
                if (srcDir != null && !srcDir.isEmpty()) {
                    for (Map.Entry<String, byte[]> e : srcDir.entrySet()) {
                        zipEntries.put(destDir + e.getKey(), e.getValue());
                    }
                } else {
                    byte[] content = findContentFromSnapshot(originalPageDirs, srcIdx);
                    if (content != null) {
                        zipEntries.put(destContent, content);
                    } else {
                        zipEntries.put(destContent, createBlankPageContentXml(page));
                    }
                }
            } else {
                log.warn("无效源页索引 {}，第 {} 页写入空白页", srcIdx, i + 1);
                zipEntries.put(destContent, createBlankPageContentXml(page));
            }
            page.setPageIndex(i);
        }

        if (docXmlPath != null) {
            updateDocumentXmlPages(zipEntries, docXmlPath, pages.size());
        } else {
            log.warn("未找到 Document.xml，页面列表可能未同步");
        }
    }

    private boolean isBlankInsertedPage(PageDTO page) {
        if (page.getSourcePageIndex() != null) return false;
        return page.getElements() == null || page.getElements().isEmpty();
    }

    private Map<Integer, Map<String, byte[]>> snapshotPageDirectories(Map<String, byte[]> zipEntries,
                                                                       List<String> contentPaths) {
        Map<Integer, Map<String, byte[]>> result = new LinkedHashMap<>();
        for (int i = 0; i < contentPaths.size(); i++) {
            String contentPath = contentPaths.get(i).replace('\\', '/');
            int slash = contentPath.lastIndexOf('/');
            if (slash < 0) continue;
            String dirPrefix = contentPath.substring(0, slash + 1);
            Map<String, byte[]> dirFiles = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : zipEntries.entrySet()) {
                String key = e.getKey().replace('\\', '/');
                if (key.startsWith(dirPrefix) && !key.endsWith("/")) {
                    dirFiles.put(key.substring(dirPrefix.length()), e.getValue());
                }
            }
            result.put(i, dirFiles);
        }
        return result;
    }

    private byte[] findContentFromSnapshot(Map<Integer, Map<String, byte[]>> snapshot, int srcIdx) {
        Map<String, byte[]> dir = snapshot.get(srcIdx);
        if (dir == null) return null;
        return dir.get("Content.xml");
    }

    private String extractDocPrefix(List<String> pageContentPaths) {
        if (pageContentPaths.isEmpty()) return "Doc_0";
        String path = pageContentPaths.get(0).replace('\\', '/');
        int idx = path.indexOf("/Pages/");
        return idx > 0 ? path.substring(0, idx) : "Doc_0";
    }

    private String findDocumentXmlPath(Map<String, byte[]> zipEntries, String docPrefix) {
        String prefix = docPrefix.replace('\\', '/');
        String[] candidates = {prefix + "/Document.xml", prefix.toLowerCase() + "/Document.xml"};
        for (String c : candidates) {
            if (zipEntries.containsKey(c)) return c;
        }
        for (String key : zipEntries.keySet()) {
            String k = key.replace('\\', '/');
            if (k.endsWith("/Document.xml") && k.startsWith(prefix.split("/")[0])) {
                return key;
            }
        }
        for (String key : zipEntries.keySet()) {
            if (key.replace('\\', '/').endsWith("/Document.xml")) return key;
        }
        return null;
    }

    private byte[] createBlankPageContentXml(PageDTO page) {
        double w = page.getWidth() != null ? page.getWidth() : 210.0;
        double h = page.getHeight() != null ? page.getHeight() : 297.0;
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ofd:Page xmlns:ofd=\"http://www.ofdspec.org/2016\">\n"
                + "  <ofd:Area>\n"
                + "    <ofd:PhysicalBox>0 0 "
                + String.format(Locale.ROOT, "%.3f %.3f", w, h)
                + "</ofd:PhysicalBox>\n"
                + "  </ofd:Area>\n"
                + "  <ofd:Content/>\n"
                + "</ofd:Page>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private void updateDocumentXmlPages(Map<String, byte[]> zipEntries,
                                        String docXmlPath,
                                        int pageCount) throws Exception {
        byte[] bytes = zipEntries.get(docXmlPath);
        Document doc = parseXml(bytes);
        Element root = doc.getDocumentElement();

        Element pagesContainer = findChildElementByLocalName(root, "Pages");
        Element samplePage = null;

        if (pagesContainer == null) {
            samplePage = findFirstChildElementByLocalName(root, "Page");
            String ns = root.getNamespaceURI();
            pagesContainer = ns != null
                    ? doc.createElementNS(ns, "Pages")
                    : doc.createElement("Pages");
            if (samplePage != null) {
                root.insertBefore(pagesContainer, samplePage);
            } else {
                root.appendChild(pagesContainer);
            }
        } else {
            samplePage = findFirstChildElementByLocalName(pagesContainer, "Page");
        }

        removeChildElementsByLocalName(pagesContainer, "Page");
        removeChildElementsByLocalName(root, "Page");

        String ns = pagesContainer.getNamespaceURI();
        for (int i = 0; i < pageCount; i++) {
            Element pageEl;
            if (samplePage != null) {
                pageEl = (Element) samplePage.cloneNode(false);
            } else {
                pageEl = ns != null ? doc.createElementNS(ns, "Page") : doc.createElement("Page");
            }
            setAttrIgnoreNs(pageEl, "ID", String.valueOf(i + 1));
            setAttrIgnoreNs(pageEl, "BaseLoc", "Pages/Page_" + i + "/Content.xml");
            pagesContainer.appendChild(pageEl);
        }

        Element commonData = findChildElementByLocalName(root, "CommonData");
        if (commonData != null) {
            setTextChildByLocalName(doc, commonData, "PageCount", String.valueOf(pageCount));
        }

        zipEntries.put(docXmlPath, serializeXml(doc));
    }

    private Element findChildElementByLocalName(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && localName.equals(localNameOf(e))) {
                return e;
            }
        }
        return null;
    }

    private Element findFirstChildElementByLocalName(Element parent, String localName) {
        return findChildElementByLocalName(parent, localName);
    }

    private void removeChildElementsByLocalName(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = children.getLength() - 1; i >= 0; i--) {
            Node n = children.item(i);
            if (n instanceof Element e && localName.equals(localNameOf(e))) {
                parent.removeChild(n);
            }
        }
    }

    private void setTextChildByLocalName(Document doc, Element parent, String localName, String text) {
        Element existing = findChildElementByLocalName(parent, localName);
        if (existing != null) {
            existing.setTextContent(text);
            return;
        }
        String ns = parent.getNamespaceURI();
        Element child = ns != null ? doc.createElementNS(ns, localName) : doc.createElement(localName);
        child.setTextContent(text);
        parent.appendChild(child);
    }

    private String localNameOf(Element e) {
        String name = e.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
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
                               Map<String, ElementDTO> dirtyElements,
                               String docPrefix) throws Exception {

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
        List<String> templateXmlPaths = findTemplateContentXmlPaths(zipEntries);
        log.info("找到 {} 个页面XML", pageXmlPaths.size());
        log.info("找到 {} 个模板XML", templateXmlPaths.size());

        for (int pageIdx = 0; pageIdx < pageXmlPaths.size(); pageIdx++) {
            String xmlPath = pageXmlPaths.get(pageIdx);
            List<ElementDTO> dirtyOnThisPage = dirtyByPage.get(pageIdx);

            if (dirtyOnThisPage == null || dirtyOnThisPage.isEmpty()) {
                log.debug("第{}页无修改，跳过", pageIdx + 1);
                continue;
            }

            LinkedHashSet<String> targets = new LinkedHashSet<>();
            targets.add(xmlPath);
            // 模板层文本也在前端可编辑，这里一并尝试严格匹配更新（新插入图片仅写入正文页）
            targets.addAll(templateXmlPaths);

            int touched = 0;
            for (String targetPath : targets) {
                byte[] xmlBytes = zipEntries.get(targetPath);
                if (xmlBytes == null) continue;
                boolean isMainPage = targetPath.equals(xmlPath);
                List<ElementDTO> batch = isMainPage
                        ? dirtyOnThisPage
                        : dirtyOnThisPage.stream()
                                .filter(el -> !Boolean.TRUE.equals(el.getIsNew()))
                                .toList();
                if (batch.isEmpty()) continue;
                byte[] patchedXml = patchOnePageXml(xmlBytes, batch, zipEntries, docPrefix, isMainPage);
                zipEntries.put(targetPath, patchedXml);
                touched++;
            }
            log.info("第{}页关联XML已处理: {} 个", pageIdx + 1, touched);
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

    private List<String> findTemplateContentXmlPaths(Map<String, byte[]> zipEntries) {
        List<String> result = new ArrayList<>();
        for (String key : zipEntries.keySet()) {
            String normalized = key.replace("\\", "/");
            if (!normalized.endsWith("Content.xml")) continue;
            if (normalized.contains("/Annots/")) continue;
            if (normalized.contains("/Tpls/") || normalized.contains("/Tpl/") || normalized.contains("/tpl")) {
                result.add(key);
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    /**
     * 修改单页XML：找到匹配的TextObject节点，更新内容
     */
    private byte[] patchOnePageXml(byte[] xmlBytes,
                                   List<ElementDTO> dirtyElements,
                                   Map<String, byte[]> zipEntries,
                                   String docPrefix,
                                   boolean allowInsertNew) throws Exception {
        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();

        for (ElementDTO el : dirtyElements) {
            try {
                if (allowInsertNew && Boolean.TRUE.equals(el.getIsNew()) && "IMAGE".equals(el.getType())) {
                    insertNewImageElement(doc, root, el, zipEntries, docPrefix);
                } else {
                    patchElement(doc, root, el);
                }
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
        List<Element> matchedNodes = new ArrayList<>();
        boolean matchedByXmlObjId = false;
        if (isNotBlank(el.getXmlObjId())) {
            Element idMatched = findByXmlObjId(nodes, el.getXmlObjId());
            if (idMatched != null) {
                matchedNodes.add(idMatched);
                matchedByXmlObjId = true;
            }
        }
        if (matchedNodes.isEmpty()) {
            matchedNodes = findTextMatchesByBoundary(nodes, el);
        }
        if (matchedNodes.isEmpty()) {
            matchedNodes = findTextMatchesByContent(nodes, el);
        }
        // 仅按坐标/内容模糊匹配时扩展同文案与区域，避免误改叠层；xmlObjId 命中则只改这一处 XML 节点
        if (!matchedByXmlObjId) {
            matchedNodes = expandBySameContent(nodes, matchedNodes, el);
            if (matchedNodes.size() <= 1) {
                matchedNodes = expandByRegion(nodes, matchedNodes, el);
            }
        }
        if (matchedNodes.isEmpty()) {
            log.warn("TEXT元素未找到匹配节点: id={}, x={}, y={}", el.getId(), el.getOriginalX(), el.getOriginalY());
            return;
        }

        for (Element matched : matchedNodes) {
            // 1. 修改几何（位置/尺寸/旋转）
            updateTextGeometry(matched, el);

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
        }
        log.info("TEXT节点已修改: matches={}, content={}, color={}, rotation={}",
                matchedNodes.size(), el.getContent(), el.getColor(), el.getRotation());
    }

    private Element findByXmlObjId(NodeList nodes, String xmlObjId) {
        if (!isNotBlank(xmlObjId)) return null;
        String target = xmlObjId.trim();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) continue;
            String id = firstNonBlank(
                    getAttrIgnoreNs(e, "ID"),
                    getAttrIgnoreNs(e, "Id"),
                    getAttrIgnoreNs(e, "id"),
                    getAttrIgnoreNs(e, "ObjID"),
                    getAttrIgnoreNs(e, "ObjectID")
            );
            if (isNotBlank(id) && target.equals(id.trim())) {
                return e;
            }
        }
        return null;
    }

    private void patchImageElement(Document doc, Element root, ElementDTO el) {
        if (Boolean.TRUE.equals(el.getIsNew())) {
            return;
        }
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

    private List<Element> findTextMatchesByBoundary(NodeList nodes, ElementDTO el) {
        List<Element> matches = new ArrayList<>();
        double ox = el.getOriginalX() != null ? el.getOriginalX() :
                (el.getX() != null ? el.getX() : 0);
        double oy = el.getOriginalY() != null ? el.getOriginalY() :
                (el.getY() != null ? el.getY() : 0);
        double ow = el.getOriginalWidth() != null ? el.getOriginalWidth() :
                (el.getWidth() != null ? el.getWidth() : 0);
        double oh = el.getOriginalHeight() != null ? el.getOriginalHeight() :
                (el.getHeight() != null ? el.getHeight() : 0);

        double bestScore = Double.MAX_VALUE;
        Element best = null;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) continue;
            double[] boundary = parseBoundaryForMatch(e);
            if (boundary == null) continue;
            double score = Math.abs(boundary[0] - ox)
                    + Math.abs(boundary[1] - oy)
                    + Math.abs(boundary[2] - ow)
                    + Math.abs(boundary[3] - oh);
            if (score <= 2.0) {
                matches.add(e);
            }
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (matches.isEmpty() && best != null && bestScore <= 2.0) {
            matches.add(best);
        }
        return matches;
    }

    private List<Element> findTextMatchesByContent(NodeList nodes, ElementDTO el) {
        List<Element> matches = new ArrayList<>();
        String target = normalizeText(el.getContent());
        if (target.isEmpty()) return matches;

        double ox = nvl(el.getOriginalX());
        double oy = nvl(el.getOriginalY());
        double bestDist = Double.MAX_VALUE;
        Element best = null;

        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) continue;
            String text = normalizeText(readTextContent(e));
            if (!target.equals(text)) continue;

            double[] boundary = parseBoundaryForMatch(e);
            if (boundary == null) continue;
            double dist = Math.abs(boundary[0] - ox) + Math.abs(boundary[1] - oy);
            if (dist <= 20.0) {
                matches.add(e);
            }
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        if (matches.isEmpty() && best != null) {
            matches.add(best);
        }
        return matches;
    }

    private String readTextContent(Element textObj) {
        StringBuilder sb = new StringBuilder();
        NodeList textCodes = getElementsByLocalName(textObj, "TextCode");
        for (int i = 0; i < textCodes.getLength(); i++) {
            String t = textCodes.item(i).getTextContent();
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 处理同内容叠层文本：把同内容节点一并更新，确保显示层也同步生效。
     */
    private List<Element> expandBySameContent(NodeList nodes, List<Element> seeds, ElementDTO el) {
        if (seeds.isEmpty()) return seeds;
        LinkedHashSet<Element> result = new LinkedHashSet<>(seeds);

        String target = normalizeText(el.getContent());
        if (target.isEmpty()) return new ArrayList<>(result);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) continue;
            if (!target.equals(normalizeText(readTextContent(e)))) continue;
            result.add(e);
        }
        return new ArrayList<>(result);
    }

    /**
     * 文本可能按字拆成多个 TextObject，若只命中1个则按区域补齐同块文本。
     */
    private List<Element> expandByRegion(NodeList nodes, List<Element> seeds, ElementDTO el) {
        if (seeds.isEmpty()) return seeds;
        LinkedHashSet<Element> result = new LinkedHashSet<>(seeds);
        double ox = nvl(el.getOriginalX());
        double oy = nvl(el.getOriginalY());
        double ow = nvl(el.getOriginalWidth());
        double oh = nvl(el.getOriginalHeight());
        if (ow <= 0 || oh <= 0) return new ArrayList<>(result);

        double x1 = ox - 2.0, y1 = oy - 2.0;
        double x2 = ox + ow + 2.0, y2 = oy + oh + 2.0;
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element e)) continue;
            double[] b = parseBoundaryForMatch(e);
            if (b == null) continue;
            double bx1 = b[0], by1 = b[1], bx2 = b[0] + b[2], by2 = b[1] + b[3];
            boolean overlap = bx2 >= x1 && bx1 <= x2 && by2 >= y1 && by1 <= y2;
            if (overlap) {
                result.add(e);
            }
        }
        return new ArrayList<>(result);
    }

    private String normalizeText(String s) {
        if (s == null) return "";
        return s
                .replace('\u00A0', ' ')
                .replace('\u3000', ' ')
                .replaceAll("\\s+", "")
                .trim();
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

    /**
     * 文本几何写回：
     * 1) 对含 CTM 的文本，优先更新 CTM 平移/旋转（保证和前端可视坐标一致）
     * 2) 对无 CTM 的文本，回退到 Boundary/Rotate 属性
     */
    private void updateTextGeometry(Element textObj, ElementDTO el) {
        boolean posChanged = isPositionChanged(el);
        boolean rotationChanged = el.getRotation() != null
                && Math.abs(nvl(el.getRotation()) - nvl(el.getOriginalRotation())) > 0.001;
        boolean sizeChanged = Math.abs(nvl(el.getWidth()) - nvl(el.getOriginalWidth())) > 0.001
                || Math.abs(nvl(el.getHeight()) - nvl(el.getOriginalHeight())) > 0.001;
        if (!posChanged && !rotationChanged && !sizeChanged) return;

        String ctmAttr = getAttrIgnoreNs(textObj, "CTM");
        List<Double> ctm = extractNumbers(ctmAttr);
        if (ctm.size() >= 6) {
            double a = ctm.get(0), b = ctm.get(1), c = ctm.get(2);
            double d = ctm.get(3), tx = ctm.get(4), ty = ctm.get(5);

            if (rotationChanged) {
                double targetRad = Math.toRadians(nvl(el.getRotation()));
                double sx = Math.hypot(a, b);
                double sy = Math.hypot(c, d);
                if (!Double.isFinite(sx) || sx <= 0) sx = 1.0;
                if (!Double.isFinite(sy) || sy <= 0) sy = 1.0;
                a = sx * Math.cos(targetRad);
                b = sx * Math.sin(targetRad);
                c = -sy * Math.sin(targetRad);
                d = sy * Math.cos(targetRad);
            }
            if (posChanged) {
                double dx = nvl(el.getX()) - nvl(el.getOriginalX());
                double dy = nvl(el.getY()) - nvl(el.getOriginalY());
                tx += dx;
                ty += dy;
            }
            setAttrIgnoreNs(textObj, "CTM",
                    String.format(Locale.ROOT, "%.6f %.6f %.6f %.6f %.6f %.6f", a, b, c, d, tx, ty));

            if (sizeChanged) {
                updateBoundarySizeOnly(textObj, el);
            }
            return;
        }

        if (posChanged || sizeChanged) {
            updateBoundaryAttr(textObj, el);
        }
        if (rotationChanged) {
            updateTextRotation(textObj, el.getRotation());
        }
    }

    /**
     * 仅更新 Boundary 尺寸，避免在 CTM 场景下把可视坐标直接写进 Boundary 导致偏移。
     */
    private void updateBoundarySizeOnly(Element textObj, ElementDTO el) {
        double[] oldBoundary = parseBoundaryAttr(textObj);
        if (oldBoundary == null) return;
        double ow = nvl(el.getOriginalWidth());
        double oh = nvl(el.getOriginalHeight());
        if (ow <= 0 || oh <= 0) return;
        double scaleW = nvl(el.getWidth()) / ow;
        double scaleH = nvl(el.getHeight()) / oh;
        double newW = oldBoundary[2] * (Double.isFinite(scaleW) && scaleW > 0 ? scaleW : 1.0);
        double newH = oldBoundary[3] * (Double.isFinite(scaleH) && scaleH > 0 ? scaleH : 1.0);
        String newBoundary = String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f",
                oldBoundary[0], oldBoundary[1], newW, newH);
        setAttrIgnoreNs(textObj, "Boundary", newBoundary);
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

            // 某些 OFD 使用 DrawParam 样式资源覆盖文本颜色；移除后使用本节点显式颜色。
            removeAttrIfExists(textObj, "DrawParam");
            removeAttrIfExists(textObj, "DrawParamID");
            removeAttrIfExists(textObj, "DrawParamRef");

            // 找 FillColor 节点
            NodeList fillColors = getElementsByLocalName(textObj, "FillColor");
            if (fillColors.getLength() > 0) {
                for (int i = 0; i < fillColors.getLength(); i++) {
                    if (fillColors.item(i) instanceof Element fc) {
                        setAttrIgnoreNs(fc, "Value",
                                rgb[0] + " " + rgb[1] + " " + rgb[2]);
                    }
                }
            } else {
                Element fc = doc.createElement("FillColor");
                setAttrIgnoreNs(fc, "Value",
                        rgb[0] + " " + rgb[1] + " " + rgb[2]);
                textObj.appendChild(fc);
            }
            setAttrIgnoreNs(textObj, "FillColor", rgb[0] + " " + rgb[1] + " " + rgb[2]);
            // 有些 OFD 引擎在 TextCode 级别读取颜色
            NodeList textCodes = getElementsByLocalName(textObj, "TextCode");
            for (int i = 0; i < textCodes.getLength(); i++) {
                if (textCodes.item(i) instanceof Element tcEl) {
                    setAttrIgnoreNs(tcEl, "FillColor", rgb[0] + " " + rgb[1] + " " + rgb[2]);
                }
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

    private void removeAttrIfExists(Element e, String attrName) {
        if (e == null || attrName == null) return;
        for (String prefix : new String[]{"", "ofd:", "ofd2:", "doc:"}) {
            String full = prefix + attrName;
            if (e.hasAttribute(full)) {
                e.removeAttribute(full);
            }
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

    // ---- 新插入图片（导入图片） ----

    private void insertNewImageElement(Document pageDoc,
                                       Element pageRoot,
                                       ElementDTO el,
                                       Map<String, byte[]> zipEntries,
                                       String docPrefix) throws Exception {
        byte[] imageBytes = decodeImageBase64(firstNonBlank(el.getImageBase64(), el.getImageData()));
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("导入图片无有效数据: id={}", el.getId());
            return;
        }

        String ext = guessImageExtension(el.getImageBase64(), el.getImageData(), imageBytes);
        int resourceId = allocateNextResourceId(zipEntries, docPrefix);
        String resFileName = "image_" + resourceId + "." + ext;
        String resZipPath = docPrefix + "/Res/" + resFileName;
        zipEntries.put(resZipPath, imageBytes);

        registerImageResource(zipEntries, docPrefix, resourceId, resFileName, ext);

        String objId = String.valueOf(allocateNextXmlObjectId(pageRoot));
        Element container = findOrCreatePageLayer(pageDoc, pageRoot);
        Element imgObj = createPageImageObjectElement(pageDoc, pageRoot, el, String.valueOf(resourceId), objId);
        container.appendChild(imgObj);

        el.setResourceId(String.valueOf(resourceId));
        el.setXmlObjId(objId);
        el.setIsNew(false);

        log.info("已插入新图片: id={}, resourceId={}, boundary=({}, {}, {}, {})",
                el.getId(), resourceId, el.getX(), el.getY(), el.getWidth(), el.getHeight());
    }

    private byte[] decodeImageBase64(String dataUrlOrBase64) {
        if (!isNotBlank(dataUrlOrBase64)) return null;
        String s = dataUrlOrBase64.trim();
        int comma = s.indexOf(',');
        if (s.startsWith("data:") && comma > 0) {
            s = s.substring(comma + 1);
        }
        try {
            return Base64.getDecoder().decode(s.replaceAll("\\s", ""));
        } catch (Exception e) {
            log.warn("Base64 解码失败: {}", e.getMessage());
            return null;
        }
    }

    private String guessImageExtension(String base64A, String base64B, byte[] bytes) {
        String src = firstNonBlank(base64A, base64B);
        if (isNotBlank(src)) {
            String lower = src.toLowerCase(Locale.ROOT);
            if (lower.contains("image/png")) return "png";
            if (lower.contains("image/jpeg") || lower.contains("image/jpg")) return "jpg";
            if (lower.contains("image/gif")) return "gif";
            if (lower.contains("image/webp")) return "webp";
            if (lower.contains("image/bmp")) return "bmp";
        }
        if (bytes != null && bytes.length >= 4) {
            if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "jpg";
            if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return "png";
            if (bytes[0] == 'G' && bytes[1] == 'I') return "gif";
            if (bytes[0] == 'B' && bytes[1] == 'M') return "bmp";
            if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[8] == 'W') return "webp";
        }
        return "png";
    }

    private int allocateNextResourceId(Map<String, byte[]> zipEntries, String docPrefix) {
        int max = 1000;
        String prefix = docPrefix.replace('\\', '/');
        for (String key : zipEntries.keySet()) {
            String k = key.replace('\\', '/');
            if (!k.startsWith(prefix + "/Res/")) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("image_(\\d+)\\.", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(k);
            if (m.find()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        String docResPath = findDocumentResXmlPath(zipEntries, prefix);
        if (docResPath != null) {
            try {
                Document resDoc = parseXml(zipEntries.get(docResPath));
                NodeList media = getElementsByLocalName(resDoc.getDocumentElement(), "MultiMedia");
                for (int i = 0; i < media.getLength(); i++) {
                    if (!(media.item(i) instanceof Element mm)) continue;
                    String id = firstNonBlank(
                            getAttrIgnoreNs(mm, "ID"),
                            getAttrIgnoreNs(mm, "Id"),
                            getAttrIgnoreNs(mm, "id"));
                    if (isNotBlank(id)) {
                        try {
                            max = Math.max(max, Integer.parseInt(id.trim()));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return max + 1;
    }

    private int allocateNextXmlObjectId(Element pageRoot) {
        int max = 0;
        NodeList all = pageRoot.getElementsByTagNameNS("*", "ImageObject");
        if (all.getLength() == 0) all = pageRoot.getElementsByTagName("ImageObject");
        for (int i = 0; i < all.getLength(); i++) {
            if (!(all.item(i) instanceof Element e)) continue;
            String id = firstNonBlank(
                    getAttrIgnoreNs(e, "ID"),
                    getAttrIgnoreNs(e, "Id"),
                    getAttrIgnoreNs(e, "id"));
            if (isNotBlank(id)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.trim()));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return max + 1;
    }

    private String findDocumentResXmlPath(Map<String, byte[]> zipEntries, String docPrefix) {
        String[] candidates = {
                docPrefix + "/DocumentRes.xml",
                docPrefix.toLowerCase(Locale.ROOT) + "/DocumentRes.xml"
        };
        for (String c : candidates) {
            if (zipEntries.containsKey(c)) return c;
        }
        for (String key : zipEntries.keySet()) {
            String k = key.replace('\\', '/');
            if (k.endsWith("/DocumentRes.xml") && k.startsWith(docPrefix.split("/")[0])) {
                return key;
            }
        }
        return null;
    }

    private void registerImageResource(Map<String, byte[]> zipEntries,
                                     String docPrefix,
                                     int resourceId,
                                     String resFileName,
                                     String ext) throws Exception {
        String docResPath = findDocumentResXmlPath(zipEntries, docPrefix);
        Document resDoc;
        Element resRoot;
        if (docResPath != null) {
            resDoc = parseXml(zipEntries.get(docResPath));
            resRoot = resDoc.getDocumentElement();
        } else {
            docResPath = docPrefix + "/DocumentRes.xml";
            resDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            resRoot = resDoc.createElementNS("http://www.ofdspec.org/2016", "ofd:Res");
            resRoot.setAttribute("xmlns:ofd", "http://www.ofdspec.org/2016");
            resDoc.appendChild(resRoot);
        }

        String ns = resRoot.getNamespaceURI();
        Element mm = ns != null
                ? resDoc.createElementNS(ns, "MultiMedia")
                : resDoc.createElement("MultiMedia");
        setAttrIgnoreNs(mm, "ID", String.valueOf(resourceId));
        setAttrIgnoreNs(mm, "Type", "Image");
        setAttrIgnoreNs(mm, "Format", ext.toUpperCase(Locale.ROOT));

        Element mediaFile = ns != null
                ? resDoc.createElementNS(ns, "MediaFile")
                : resDoc.createElement("MediaFile");
        mediaFile.setTextContent("Res/" + resFileName);
        mm.appendChild(mediaFile);
        resRoot.appendChild(mm);

        zipEntries.put(docResPath, serializeXml(resDoc));
    }

    private Element findOrCreatePageLayer(Document doc, Element pageRoot) {
        Element content = findChildElementByLocalName(pageRoot, "Content");
        if (content == null) {
            String ns = pageRoot.getNamespaceURI();
            content = ns != null ? doc.createElementNS(ns, "Content") : doc.createElement("Content");
            pageRoot.appendChild(content);
        }

        Element layer = findChildElementByLocalName(content, "Layer");
        if (layer != null) return layer;

        String ns = content.getNamespaceURI();
        if (ns == null) ns = pageRoot.getNamespaceURI();
        layer = ns != null ? doc.createElementNS(ns, "Layer") : doc.createElement("Layer");
        setAttrIgnoreNs(layer, "ID", "1");
        content.appendChild(layer);
        return layer;
    }

    private Element createPageImageObjectElement(Document doc,
                                                 Element pageRoot,
                                                 ElementDTO el,
                                                 String resourceId,
                                                 String objId) {
        String ns = pageRoot.getNamespaceURI();
        Element img = ns != null ? doc.createElementNS(ns, "ImageObject") : doc.createElement("ImageObject");
        setAttrIgnoreNs(img, "ID", objId);
        setAttrIgnoreNs(img, "ResourceID", resourceId);
        String boundary = String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f",
                nvl(el.getX()), nvl(el.getY()), nvl(el.getWidth()), nvl(el.getHeight()));
        setAttrIgnoreNs(img, "Boundary", boundary);
        if (el.getRotation() != null && Math.abs(el.getRotation()) > 0.001) {
            setAttrIgnoreNs(img, "CTM", buildRotationCtm(el.getRotation(), nvl(el.getX()), nvl(el.getY())));
        }
        return img;
    }

    private String buildRotationCtm(double rotationDeg, double tx, double ty) {
        double rad = Math.toRadians(rotationDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return String.format(Locale.ROOT, "%.6f %.6f %.6f %.6f %.6f %.6f",
                cos, sin, -sin, cos, tx, ty);
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

    private String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
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
        List<String> pagePaths = findPageContentXmlPaths(zipEntries);
        String docPrefix = extractDocPrefix(pagePaths);

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

            byte[] patchedXml = patchAnnotationXml(xmlBytes, annotations, zipEntries, docPrefix);
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
        List<String> pagePaths = findPageContentXmlPaths(zipEntries);
        String docPrefix = extractDocPrefix(pagePaths);
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
        Element annEl = createAnnotationElement(doc, annotation, zipEntries, docPrefix);
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
        List<String> pagePaths = findPageContentXmlPaths(zipEntries);
        String docPrefix = extractDocPrefix(pagePaths);
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
        Element annEl = createAnnotationElement(doc, updated, zipEntries, docPrefix);
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

    private byte[] patchAnnotationXml(byte[] xmlBytes, List<AnnotationDTO> annotations,
                                      Map<String, byte[]> zipEntries, String docPrefix) throws Exception {
        Document doc = parseXml(xmlBytes);
        Element root = doc.getDocumentElement();
        for (AnnotationDTO ann : annotations) {
            Element el = createAnnotationElement(doc, ann, zipEntries, docPrefix);
            if (el != null) root.appendChild(el);
        }
        return serializeXml(doc);
    }

    private Element createAnnotationElement(Document doc, AnnotationDTO ann,
                                            Map<String, byte[]> zipEntries, String docPrefix) {
        if (ann == null || ann.getType() == null) return null;
        return switch (ann.getType()) {
            case "STAMP"                                                         -> createStampElement(doc, ann, zipEntries, docPrefix);
            case "TEXTBOX", "STICKYNOTE"                                         -> createTextElement(doc, ann);
            case "HIGHLIGHT", "UNDERLINE", "STRIKEOUT",
                 "RECTANGLE", "CIRCLE", "ARROW", "FREEHAND"                     -> createPathElement(doc, ann);
            default -> {
                log.warn("未知注释类型: {}，使用 PATH 兜底", ann.getType());
                yield createPathElement(doc, ann);
            }
        };
    }

    /**
     * OFD 图章：Annots 层 ImageObject + ResourceID，图片写入 Doc_x/Res/ 并在 DocumentRes 注册
     */
    private Element createStampElement(Document doc, AnnotationDTO ann,
                                       Map<String, byte[]> zipEntries, String docPrefix) {
        Element root = doc.getDocumentElement();
        String ns = root != null ? root.getNamespaceURI() : null;
        Element el = ns != null ? doc.createElementNS(ns, "ImageObject") : doc.createElement("ImageObject");
        setBoundaryAttr(el, ann);

        if (zipEntries != null && isNotBlank(docPrefix) && isNotBlank(ann.getStampBase64())) {
            byte[] imageBytes = decodeImageBase64(ann.getStampBase64());
            if (imageBytes != null && imageBytes.length > 0) {
                try {
                    int resourceId = allocateNextResourceId(zipEntries, docPrefix);
                    String ext = guessImageExtension(ann.getStampBase64(), null, imageBytes);
                    String resFileName = "image_" + resourceId + "." + ext;
                    zipEntries.put(docPrefix + "/Res/" + resFileName, imageBytes);
                    registerImageResource(zipEntries, docPrefix, resourceId, resFileName, ext);
                    setAttrIgnoreNs(el, "ResourceID", String.valueOf(resourceId));
                } catch (Exception e) {
                    log.warn("图章资源写入失败: {}", e.getMessage());
                }
            }
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