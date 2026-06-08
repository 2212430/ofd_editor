package com.ofdeditor.service;

import com.ofdeditor.util.SplitPayloadUtil;
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
 * 按页码将 OFD 拆成两个独立包（保留资源与页面目录结构）。
 */
@Slf4j
@Service
public class OfdSplitService {

    private static final Pattern PAGE_DIR_PATTERN =
            Pattern.compile("Page_(\\d+)", Pattern.CASE_INSENSITIVE);

    public record SplitPair(byte[] part1, byte[] part2) {}

    /**
     * @param splitAfterPage 第一部分最后一页页码（1-based，例如 3 表示第 1～3 页在第一份）
     */
    public SplitPair split(byte[] ofdBytes, int splitAfterPage) throws Exception {
        Map<String, byte[]> zip = unzipToMap(ofdBytes);
        List<String> pagePaths = findPageContentXmlPaths(zip);
        int total = pagePaths.size();
        SplitPayloadUtil.validateSplit(total, splitAfterPage);

        int part1End = splitAfterPage;
        byte[] part1 = buildSubset(zip, pagePaths, 0, part1End);
        byte[] part2 = buildSubset(zip, pagePaths, part1End, total);

        log.info("OFD 拆分完成: 共 {} 页, 拆分点={}, 第一部分 {} 页, 第二部分 {} 页",
                total, splitAfterPage, part1End, total - part1End);
        return new SplitPair(part1, part2);
    }

    public int countPages(byte[] ofdBytes) throws Exception {
        return findPageContentXmlPaths(unzipToMap(ofdBytes)).size();
    }

    private byte[] buildSubset(Map<String, byte[]> source,
                               List<String> allPagePaths,
                               int fromInclusive,
                               int toExclusive) throws Exception {
        Map<String, byte[]> zip = new LinkedHashMap<>(source);
        String docPrefix = extractDocPrefix(allPagePaths);
        String pagesPrefix = normalize(docPrefix) + "/Pages/";
        String annotPrefix = normalize(docPrefix) + "/Annots/";

        zip.keySet().removeIf(k -> {
            String norm = normalize(k);
            return norm.startsWith(pagesPrefix) || norm.startsWith(annotPrefix);
        });

        Map<Integer, Map<String, byte[]>> snapshots = snapshotPageDirectories(source, allPagePaths);
        int destCount = toExclusive - fromInclusive;
        for (int dest = 0, src = fromInclusive; src < toExclusive; src++, dest++) {
            Map<String, byte[]> dir = snapshots.get(src);
            if (dir != null && !dir.isEmpty()) {
                for (Map.Entry<String, byte[]> e : dir.entrySet()) {
                    zip.put(docPrefix + "/Pages/Page_" + dest + "/" + e.getKey(), e.getValue());
                }
            } else {
                byte[] content = source.get(allPagePaths.get(src));
                if (content != null) {
                    zip.put(docPrefix + "/Pages/Page_" + dest + "/Content.xml", content);
                }
            }
            copyAnnotsForPage(source, zip, docPrefix, src, dest);
        }

        String docXmlPath = findDocumentXmlPath(zip, docPrefix);
        if (docXmlPath != null) {
            updateDocumentXmlPages(zip, docXmlPath, destCount);
        }
        return zipFromMap(zip);
    }

    private void copyAnnotsForPage(Map<String, byte[]> source,
                                   Map<String, byte[]> dest,
                                   String docPrefix,
                                   int srcPageIdx,
                                   int destPageIdx) {
        String srcPrefix = normalize(docPrefix) + "/Annots/Page_" + srcPageIdx + "/";
        String destPrefix = normalize(docPrefix) + "/Annots/Page_" + destPageIdx + "/";
        for (Map.Entry<String, byte[]> e : source.entrySet()) {
            String norm = normalize(e.getKey());
            if (norm.startsWith(srcPrefix)) {
                dest.put(destPrefix + norm.substring(srcPrefix.length()), e.getValue());
            }
        }
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

    private void setAttrIgnoreNs(Element e, String attrName, String value) {
        e.setAttribute(attrName, value);
    }

    private String localNameOf(Element e) {
        String name = e.getNodeName();
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }
}
