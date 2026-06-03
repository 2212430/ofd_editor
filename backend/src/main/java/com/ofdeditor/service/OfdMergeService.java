package com.ofdeditor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 将两个 OFD 包按页序合并：第一个文件的全部页面在前，第二个在后。
 */
@Slf4j
@Service
public class OfdMergeService {

    private static final Pattern PAGE_DIR_PATTERN =
            Pattern.compile("Page_(\\d+)", Pattern.CASE_INSENSITIVE);
    /** 合并第二个 OFD 时需重映射的资源类属性（发票/版式文档常用 DrawParam、Font） */
    private static final String[] REMAP_RESOURCE_ATTRS = {
            "ResourceID", "Font", "DrawParam", "DrawParamID", "DrawParamRef",
    };
    private static final String TEMPLATE_ID_ATTR = "TemplateID";
    private static final String[] RES_CHILD_TAGS = {
            "MultiMedia", "Font", "ColorSpace", "DrawParam", "CompositeGraphicUnit",
    };

    /**
     * @return 合并后的 OFD 字节
     */
    public byte[] mergeTwoOfd(byte[] firstOfd, byte[] secondOfd) throws Exception {
        Map<String, byte[]> base = unzipToMap(firstOfd);
        Map<String, byte[]> addon = unzipToMap(secondOfd);

        List<String> basePagePaths = findPageContentXmlPaths(base);
        List<String> addonPagePaths = findPageContentXmlPaths(addon);
        if (basePagePaths.isEmpty()) {
            throw new IllegalArgumentException("第一个 OFD 未找到页面内容");
        }
        if (addonPagePaths.isEmpty()) {
            throw new IllegalArgumentException("第二个 OFD 未找到页面内容");
        }

        String basePrefix = extractDocPrefix(basePagePaths);
        String addonPrefix = extractDocPrefix(addonPagePaths);
        int basePageCount = basePagePaths.size();

        Map<String, String> resourceIdRemap = mergeAddonResources(base, addon, basePrefix, addonPrefix);
        Map<String, String> templateIdRemap = mergeAddonTemplates(
                base, addon, basePrefix, addonPrefix, resourceIdRemap);
        refreshResMaxUnitId(base, basePrefix);

        Map<Integer, Map<String, byte[]>> addonPageDirs = snapshotPageDirectories(addon, addonPagePaths);

        for (int i = 0; i < addonPagePaths.size(); i++) {
            int destIdx = basePageCount + i;
            String destDir = basePrefix + "/Pages/Page_" + destIdx + "/";
            Map<String, byte[]> srcDir = addonPageDirs.get(i);
            if (srcDir == null || srcDir.isEmpty()) {
                byte[] content = readContentXml(addon, addonPagePaths.get(i));
                if (content != null) {
                    base.put(destDir + "Content.xml",
                            remapRefsInXml(content, resourceIdRemap, templateIdRemap));
                }
                continue;
            }
            for (Map.Entry<String, byte[]> e : srcDir.entrySet()) {
                byte[] data = e.getValue();
                if (e.getKey().toLowerCase(Locale.ROOT).endsWith(".xml")) {
                    data = remapRefsInXml(data, resourceIdRemap, templateIdRemap);
                }
                base.put(destDir + e.getKey(), data);
            }
        }

        mergeAddonAnnotations(base, addon, basePrefix, addonPrefix, basePageCount,
                resourceIdRemap, templateIdRemap);

        String docXmlPath = findDocumentXmlPath(base, basePrefix);
        if (docXmlPath != null) {
            updateDocumentXmlPages(base, docXmlPath, basePageCount + addonPagePaths.size());
        }

        byte[] merged = zipFromMap(base);
        log.info("OFD 合并完成：{} 页 + {} 页 → {} 页，输出 {} bytes",
                basePageCount, addonPagePaths.size(), basePageCount + addonPagePaths.size(), merged.length);
        return merged;
    }

    private Map<String, String> mergeAddonResources(Map<String, byte[]> base,
                                                    Map<String, byte[]> addon,
                                                    String basePrefix,
                                                    String addonPrefix) throws Exception {
        Map<String, String> idRemap = new LinkedHashMap<>();
        final int[] nextIdHolder = { findMaxResourceId(base, basePrefix) + 1 };

        String baseResPrefix = normalize(basePrefix) + "/Res/";
        String addonResPrefix = normalize(addonPrefix) + "/Res/";
        Set<String> usedFileNames = new HashSet<>();
        for (String key : base.keySet()) {
            String norm = normalize(key);
            if (norm.startsWith(baseResPrefix)) {
                usedFileNames.add(fileName(norm));
            }
        }

        Map<String, String> mediaFileRename = new HashMap<>();
        for (String key : new ArrayList<>(addon.keySet())) {
            String norm = normalize(key);
            if (!norm.startsWith(addonResPrefix)) continue;
            String name = fileName(norm);
            String destName = name;
            if (usedFileNames.contains(destName)) {
                destName = "merge_" + nextIdHolder[0] + "_" + name;
            }
            usedFileNames.add(destName);
            mediaFileRename.put(name, destName);
            base.put(baseResPrefix + destName, addon.get(key));
        }

        mergeResXmlEntries(base, addon, basePrefix, addonPrefix, "/DocumentRes.xml",
                idRemap, mediaFileRename, nextIdHolder);
        mergeResXmlEntries(base, addon, basePrefix, addonPrefix, "/PublicRes.xml",
                idRemap, mediaFileRename, nextIdHolder);

        return idRemap;
    }

    private void mergeResXmlEntries(Map<String, byte[]> base,
                                   Map<String, byte[]> addon,
                                   String basePrefix,
                                   String addonPrefix,
                                   String suffix,
                                   Map<String, String> idRemap,
                                   Map<String, String> mediaFileRename,
                                   int[] nextIdHolder) throws Exception {
        String addonPath = findResXmlPath(addon, addonPrefix, suffix);
        if (addonPath == null) return;

        String basePath = findResXmlPath(base, basePrefix, suffix);
        Document baseDoc;
        Element baseRoot;
        if (basePath != null) {
            baseDoc = parseXml(base.get(basePath));
            baseRoot = baseDoc.getDocumentElement();
        } else {
            basePath = normalize(basePrefix) + suffix;
            baseDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            baseRoot = baseDoc.createElement("Res");
            baseDoc.appendChild(baseRoot);
        }

        Document addonDoc = parseXml(addon.get(addonPath));
        Element addonRoot = addonDoc.getDocumentElement();
        appendResChildren(addonRoot, baseDoc, baseRoot, idRemap, mediaFileRename, nextIdHolder);
        base.put(basePath, serializeXml(baseDoc));
    }

    private void appendResChildren(Element addonRoot,
                                   Document baseDoc,
                                   Element baseRoot,
                                   Map<String, String> idRemap,
                                   Map<String, String> mediaFileRename,
                                   int[] nextIdHolder) {
        for (String tag : RES_CHILD_TAGS) {
            NodeList nodes = getElementsByLocalName(addonRoot, tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element src)) continue;
                String oldId = firstNonBlank(
                        getAttrIgnoreNs(src, "ID"),
                        getAttrIgnoreNs(src, "Id"),
                        getAttrIgnoreNs(src, "id"));
                if (!isNotBlank(oldId)) continue;
                String oldKey = oldId.trim();
                if (idRemap.containsKey(oldKey)) continue;

                int newId = nextIdHolder[0]++;
                idRemap.put(oldKey, String.valueOf(newId));

                Element cloned = (Element) src.cloneNode(true);
                setAttrIgnoreNs(cloned, "ID", String.valueOf(newId));
                setAttrIgnoreNs(cloned, "Id", String.valueOf(newId));
                setAttrIgnoreNs(cloned, "id", String.valueOf(newId));

                String mediaFile = getFirstTextByLocalName(cloned, "MediaFile");
                if (isNotBlank(mediaFile)) {
                    String trimmed = mediaFile.trim();
                    String baseName = fileName(trimmed);
                    String renamed = mediaFileRename.getOrDefault(baseName, baseName);
                    setTextChildByLocalName(baseDoc, cloned, "MediaFile", renamed);
                }
                baseRoot.appendChild(baseDoc.importNode(cloned, true));
            }
        }
    }

    /**
     * 合并第二个 OFD 的模板层（Tpls）：发票边框、标题、底图等通常在此层。
     */
    private Map<String, String> mergeAddonTemplates(Map<String, byte[]> base,
                                                    Map<String, byte[]> addon,
                                                    String basePrefix,
                                                    String addonPrefix,
                                                    Map<String, String> resourceIdRemap) throws Exception {
        Map<String, String> templateIdRemap = new LinkedHashMap<>();
        String addonDocPath = findDocumentXmlPath(addon, addonPrefix);
        if (addonDocPath == null) return templateIdRemap;

        Document addonDocXml = parseXml(addon.get(addonDocPath));
        NodeList addonTplPages = getElementsByLocalName(addonDocXml.getDocumentElement(), "TemplatePage");
        if (addonTplPages.getLength() == 0) {
            mergeAddonTemplatesByScan(base, addon, basePrefix, addonPrefix, resourceIdRemap, templateIdRemap);
            return templateIdRemap;
        }

        String baseDocPath = findDocumentXmlPath(base, basePrefix);
        if (baseDocPath == null) return templateIdRemap;

        Document baseDocXml = parseXml(base.get(baseDocPath));
        Element baseDocRoot = baseDocXml.getDocumentElement();
        Element tplParent = findTemplatePageParent(baseDocRoot);
        if (tplParent == null) tplParent = baseDocRoot;

        final int[] nextTplId = { findMaxTemplateId(base, basePrefix) + 1 };
        final int[] tplSeq = { findMaxTplDirSeq(base, basePrefix) };

        for (int i = 0; i < addonTplPages.getLength(); i++) {
            if (!(addonTplPages.item(i) instanceof Element addonTpl)) continue;
            String oldId = firstNonBlank(
                    getAttrIgnoreNs(addonTpl, "ID"),
                    getAttrIgnoreNs(addonTpl, "Id"),
                    getAttrIgnoreNs(addonTpl, "id"));
            String baseLoc = getAttrIgnoreNs(addonTpl, "BaseLoc");
            if (!isNotBlank(oldId) || !isNotBlank(baseLoc)) continue;

            String newId = String.valueOf(nextTplId[0]++);
            String newTplDir = "Tpls/Tpl_merge_" + (tplSeq[0]++) + "/";
            String fromDirPrefix = resolveTplDirPrefix(addonPrefix, baseLoc.trim());
            String toDirPrefix = normalize(basePrefix) + "/" + newTplDir;
            copyDirectoryWithRemap(addon, base, fromDirPrefix, toDirPrefix, resourceIdRemap, templateIdRemap);

            templateIdRemap.put(oldId.trim(), newId);

            Element cloned = (Element) addonTpl.cloneNode(true);
            setAttrIgnoreNs(cloned, "ID", newId);
            setAttrIgnoreNs(cloned, "Id", newId);
            setAttrIgnoreNs(cloned, "id", newId);
            setAttrIgnoreNs(cloned, "BaseLoc", newTplDir + "Content.xml");
            tplParent.appendChild(baseDocXml.importNode(cloned, true));
            log.debug("合并模板: {} -> {}, BaseLoc={}", oldId, newId, newTplDir);
        }

        base.put(baseDocPath, serializeXml(baseDocXml));
        return templateIdRemap;
    }

    /** Document.xml 无 TemplatePage 时，扫描 Tpls 目录 */
    private void mergeAddonTemplatesByScan(Map<String, byte[]> base,
                                           Map<String, byte[]> addon,
                                           String basePrefix,
                                           String addonPrefix,
                                           Map<String, String> resourceIdRemap,
                                           Map<String, String> templateIdRemap) {
        String addonTpls = normalize(addonPrefix) + "/Tpls/";
        Set<String> copiedDirs = new HashSet<>();
        final int[] tplSeq = { findMaxTplDirSeq(base, basePrefix) };

        for (String key : addon.keySet()) {
            String norm = normalize(key);
            if (!norm.startsWith(addonTpls) || !norm.endsWith("/Content.xml")) continue;
            int tplStart = norm.indexOf("/Tpls/") + 6;
            int contentIdx = norm.indexOf("/Content.xml", tplStart);
            if (contentIdx <= tplStart) continue;
            String tplFolder = norm.substring(tplStart, contentIdx + 1);
            if (!copiedDirs.add(tplFolder)) continue;

            String newTplDir = "Tpls/Tpl_merge_" + (tplSeq[0]++) + "/";
            String fromDirPrefix = normalize(addonPrefix) + "/" + tplFolder;
            String toDirPrefix = normalize(basePrefix) + "/" + newTplDir;
            copyDirectoryWithRemap(addon, base, fromDirPrefix, toDirPrefix, resourceIdRemap, templateIdRemap);
        }
    }

    private Element findTemplatePageParent(Element docRoot) {
        NodeList tpls = getElementsByLocalName(docRoot, "TemplatePage");
        if (tpls.getLength() > 0 && tpls.item(0).getParentNode() instanceof Element parent) {
            return parent;
        }
        Element common = findChildElementByLocalName(docRoot, "CommonData");
        if (common != null) return common;
        return docRoot;
    }

    private String resolveTplDirPrefix(String docPrefix, String baseLoc) {
        String loc = normalize(baseLoc);
        if (loc.endsWith("Content.xml")) {
            int idx = loc.lastIndexOf('/');
            loc = idx > 0 ? loc.substring(0, idx + 1) : loc;
        }
        if (!loc.endsWith("/")) loc += "/";
        return normalize(docPrefix) + "/" + loc;
    }

    private void copyDirectoryWithRemap(Map<String, byte[]> from,
                                        Map<String, byte[]> to,
                                        String fromDirPrefix,
                                        String toDirPrefix,
                                        Map<String, String> resourceIdRemap,
                                        Map<String, String> templateIdRemap) {
        String fromNorm = normalize(fromDirPrefix);
        if (!fromNorm.endsWith("/")) fromNorm += "/";
        String toNorm = normalize(toDirPrefix);
        if (!toNorm.endsWith("/")) toNorm += "/";

        for (Map.Entry<String, byte[]> e : from.entrySet()) {
            String key = normalize(e.getKey());
            if (!key.startsWith(fromNorm)) continue;
            String rel = key.substring(fromNorm.length());
            byte[] data = e.getValue();
            if (rel.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                data = remapRefsInXml(data, resourceIdRemap, templateIdRemap);
            }
            to.put(toNorm + rel, data);
        }
    }

    private int findMaxTemplateId(Map<String, byte[]> zip, String docPrefix) {
        int max = 0;
        String docPath = findDocumentXmlPath(zip, docPrefix);
        if (docPath == null) return max;
        try {
            Document doc = parseXml(zip.get(docPath));
            NodeList nodes = getElementsByLocalName(doc.getDocumentElement(), "TemplatePage");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) continue;
                String id = firstNonBlank(
                        getAttrIgnoreNs(el, "ID"),
                        getAttrIgnoreNs(el, "Id"),
                        getAttrIgnoreNs(el, "id"));
                if (isNotBlank(id)) {
                    try {
                        max = Math.max(max, Integer.parseInt(id.trim()));
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return max;
    }

    private int findMaxTplDirSeq(Map<String, byte[]> zip, String docPrefix) {
        int max = -1;
        Pattern p = Pattern.compile("Tpl_(?:merge_)?(\\d+)", Pattern.CASE_INSENSITIVE);
        String tplsPrefix = normalize(docPrefix) + "/Tpls/";
        for (String key : zip.keySet()) {
            String norm = normalize(key);
            if (!norm.startsWith(tplsPrefix)) continue;
            Matcher m = p.matcher(norm);
            if (m.find()) {
                try {
                    max = Math.max(max, Integer.parseInt(m.group(1)));
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return Math.max(max, 0);
    }

    private void refreshResMaxUnitId(Map<String, byte[]> zip, String docPrefix) throws Exception {
        for (String suffix : List.of("/DocumentRes.xml", "/PublicRes.xml")) {
            String path = findResXmlPath(zip, docPrefix, suffix);
            if (path == null) continue;
            Document doc = parseXml(zip.get(path));
            Element root = doc.getDocumentElement();
            int maxId = findMaxElementIdInTree(root);
            if (maxId > 0) {
                setAttrIgnoreNs(root, "MaxUnitID", String.valueOf(maxId));
            }
            zip.put(path, serializeXml(doc));
        }
    }

    private int findMaxElementIdInTree(Element root) {
        int max = 0;
        ArrayDeque<Element> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Element el = stack.pop();
            String id = firstNonBlank(
                    getAttrIgnoreNs(el, "ID"),
                    getAttrIgnoreNs(el, "Id"),
                    getAttrIgnoreNs(el, "id"));
            if (isNotBlank(id)) {
                try {
                    max = Math.max(max, Integer.parseInt(id.trim()));
                } catch (NumberFormatException ignore) {
                }
            }
            NodeList children = el.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element child) {
                    stack.push(child);
                }
            }
        }
        return max;
    }

    private void mergeAddonAnnotations(Map<String, byte[]> base,
                                       Map<String, byte[]> addon,
                                       String basePrefix,
                                       String addonPrefix,
                                       int pageOffset,
                                       Map<String, String> resourceIdRemap,
                                       Map<String, String> templateIdRemap) {
        String addonAnnotPrefix = normalize(addonPrefix) + "/Annots/";
        for (Map.Entry<String, byte[]> e : addon.entrySet()) {
            String norm = normalize(e.getKey());
            if (!norm.startsWith(addonAnnotPrefix)) continue;
            Matcher m = PAGE_DIR_PATTERN.matcher(norm);
            if (!m.find()) continue;
            int oldPage = Integer.parseInt(m.group(1));
            int newPage = oldPage + pageOffset;
            String dest = norm.replace(addonAnnotPrefix, normalize(basePrefix) + "/Annots/")
                    .replaceFirst("Page_\\d+", "Page_" + newPage);
            byte[] data = e.getValue();
            if (norm.endsWith(".xml")) {
                data = remapRefsInXml(data, resourceIdRemap, templateIdRemap);
            }
            base.put(dest, data);
        }
    }

    private byte[] remapRefsInXml(byte[] xmlBytes,
                                  Map<String, String> resourceIdRemap,
                                  Map<String, String> templateIdRemap) {
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);
        for (String attr : REMAP_RESOURCE_ATTRS) {
            xml = remapNumericAttr(xml, resourceIdRemap, attr);
        }
        xml = remapNumericAttr(xml, templateIdRemap, TEMPLATE_ID_ATTR);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private String remapNumericAttr(String xml, Map<String, String> idRemap, String attrName) {
        if (idRemap == null || idRemap.isEmpty()) return xml;
        Pattern p = Pattern.compile(
                "(" + Pattern.quote(attrName) + "\\s*=\\s*[\"'])(\\d+)([\"'])",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(xml);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String oldId = m.group(2);
            String newId = idRemap.getOrDefault(oldId, oldId);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + newId + m.group(3)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private int findMaxResourceId(Map<String, byte[]> zipEntries, String docPrefix) {
        int max = 0;
        String prefix = normalize(docPrefix);
        String docResPath = findResXmlPath(zipEntries, docPrefix, "/DocumentRes.xml");
        if (docResPath == null) {
            docResPath = findResXmlPath(zipEntries, docPrefix, "/PublicRes.xml");
        }
        if (docResPath != null) {
            try {
                Document resDoc = parseXml(zipEntries.get(docResPath));
                for (String tag : RES_CHILD_TAGS) {
                    NodeList nodes = getElementsByLocalName(resDoc.getDocumentElement(), tag);
                    for (int i = 0; i < nodes.getLength(); i++) {
                        if (!(nodes.item(i) instanceof Element el)) continue;
                        String id = firstNonBlank(
                                getAttrIgnoreNs(el, "ID"),
                                getAttrIgnoreNs(el, "Id"),
                                getAttrIgnoreNs(el, "id"));
                        if (isNotBlank(id)) {
                            try {
                                max = Math.max(max, Integer.parseInt(id.trim()));
                            } catch (NumberFormatException ignore) {
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return max;
    }

    private void updateDocumentXmlPages(Map<String, byte[]> zipEntries,
                                        String docXmlPath,
                                        int pageCount) throws Exception {
        Document doc = parseXml(zipEntries.get(docXmlPath));
        Element root = doc.getDocumentElement();

        Element pagesContainer = findChildElementByLocalName(root, "Pages");
        Element samplePage = null;
        if (pagesContainer == null) {
            samplePage = findChildElementByLocalName(root, "Page");
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
            samplePage = findChildElementByLocalName(pagesContainer, "Page");
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

    private Map<Integer, Map<String, byte[]>> snapshotPageDirectories(Map<String, byte[]> zipEntries,
                                                                      List<String> contentPaths) {
        Map<Integer, Map<String, byte[]>> result = new LinkedHashMap<>();
        for (int i = 0; i < contentPaths.size(); i++) {
            String contentPath = normalize(contentPaths.get(i));
            int slash = contentPath.lastIndexOf('/');
            if (slash < 0) continue;
            String dirPrefix = contentPath.substring(0, slash + 1);
            Map<String, byte[]> dirFiles = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : zipEntries.entrySet()) {
                String key = normalize(e.getKey());
                if (key.startsWith(dirPrefix) && !key.endsWith("/")) {
                    dirFiles.put(key.substring(dirPrefix.length()), e.getValue());
                }
            }
            result.put(i, dirFiles);
        }
        return result;
    }

    private byte[] readContentXml(Map<String, byte[]> zip, String contentPath) {
        return zip.get(contentPath);
    }

    private List<String> findPageContentXmlPaths(Map<String, byte[]> zipEntries) {
        List<String> result = new ArrayList<>();
        for (String key : zipEntries.keySet()) {
            if (normalize(key).matches(".*/Pages/Page_\\d+/Content\\.xml")) {
                result.add(key);
            }
        }
        result.sort(Comparator.comparingInt(this::extractPageNumber));
        return result;
    }

    private int extractPageNumber(String path) {
        Matcher m = PAGE_DIR_PATTERN.matcher(path);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private String extractDocPrefix(List<String> pageContentPaths) {
        if (pageContentPaths.isEmpty()) return "Doc_0";
        String path = normalize(pageContentPaths.get(0));
        int idx = path.indexOf("/Pages/");
        return idx > 0 ? path.substring(0, idx) : "Doc_0";
    }

    private String findDocumentXmlPath(Map<String, byte[]> zipEntries, String docPrefix) {
        String prefix = normalize(docPrefix);
        for (String c : new String[]{prefix + "/Document.xml", prefix.toLowerCase(Locale.ROOT) + "/Document.xml"}) {
            if (zipEntries.containsKey(c)) return c;
        }
        for (String key : zipEntries.keySet()) {
            String k = normalize(key);
            if (k.endsWith("/Document.xml") && k.startsWith(prefix.split("/")[0])) {
                return key;
            }
        }
        return null;
    }

    private String findResXmlPath(Map<String, byte[]> zipEntries, String docPrefix, String suffix) {
        String path = normalize(docPrefix) + suffix;
        if (zipEntries.containsKey(path)) return path;
        String lower = normalize(docPrefix).toLowerCase(Locale.ROOT) + suffix;
        if (zipEntries.containsKey(lower)) return lower;
        for (String key : zipEntries.keySet()) {
            if (normalize(key).endsWith(suffix)) return key;
        }
        return null;
    }

    private Map<String, byte[]> unzipToMap(byte[] ofdBytes) throws Exception {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(ofdBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    map.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        return map;
    }

    private byte[] zipFromMap(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private byte[] serializeXml(Document doc) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(new OutputStreamWriter(baos, StandardCharsets.UTF_8)));
        return baos.toByteArray();
    }

    private NodeList getElementsByLocalName(Element root, String localName) {
        NodeList ns = root.getElementsByTagNameNS("*", localName);
        return ns.getLength() > 0 ? ns : root.getElementsByTagName(localName);
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

    private String getFirstTextByLocalName(Element parent, String localName) {
        Element child = findChildElementByLocalName(parent, localName);
        return child != null ? child.getTextContent() : null;
    }

    private String getAttrIgnoreNs(Element e, String attrName) {
        String v = e.getAttribute(attrName);
        if (v != null && !v.isEmpty()) return v;
        return null;
    }

    private void setAttrIgnoreNs(Element e, String attrName, String value) {
        e.setAttribute(attrName, value);
    }

    private String localNameOf(Element e) {
        String name = e.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private String fileName(String path) {
        String norm = normalize(path);
        int slash = norm.lastIndexOf('/');
        return slash >= 0 ? norm.substring(slash + 1) : norm;
    }
}
