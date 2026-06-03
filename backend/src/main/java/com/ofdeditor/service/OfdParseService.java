package com.ofdeditor.service;

import com.ofdeditor.dto.ElementDTO;
import com.ofdeditor.dto.OfdDocumentDTO;
import com.ofdeditor.dto.PageDTO;
import lombok.extern.slf4j.Slf4j;
import org.ofdrw.converter.ImageMaker;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.basicType.ST_ID;
import org.ofdrw.core.basicType.ST_RefID;
import org.ofdrw.core.signatures.appearance.StampAnnot;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.reader.model.StampAnnotEntity;
import org.ofdrw.reader.tools.ImageUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import com.ofdeditor.dto.AnnotationDTO;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Service
public class OfdParseService {

    private static final Set<String> RENDERABLE_TYPES = Set.of(
            "TextObject", "ImageObject", "PathObject",
            "CT_Text", "CT_Image", "CT_Path"
    );
    private static final Set<String> RESOURCE_CLASS_KEYWORDS = Set.of(
            "multimedia", "imageresource", "mediaresource",
            "imageobject", "imagemedia", "ctmedia", "ctimage"
    );
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "bmp", "gif",
            "tif", "tiff", "jb2", "jbig2", "webp"
    );

    // ==================== 入口 ====================

    public OfdDocumentDTO parseOfd(MultipartFile file) throws Exception {
        Path tempFile = Files.createTempFile("ofd_upload_", ".ofd");
        file.transferTo(tempFile);
        try {
            return parseOfdFromPath(tempFile, file.getOriginalFilename());
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    public OfdDocumentDTO parseOfdBytes(byte[] fileBytes, String originalFilename) throws Exception {
        Path tempFile = Files.createTempFile("ofd_upload_", ".ofd");
        try {
            Files.write(tempFile, fileBytes);
            return parseOfdFromPath(tempFile, originalFilename);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private OfdDocumentDTO parseOfdFromPath(Path tempFile, String originalFilename) throws Exception {
        OfdDocumentDTO documentDTO = new OfdDocumentDTO();
        List<PageDTO> pages = new ArrayList<>();

        try (OFDReader reader = new OFDReader(tempFile);
             ZipFile zipFile = new ZipFile(tempFile.toFile())) {

            ParseContext ctx = buildParseContext(tempFile, reader, zipFile);
            loadDigitalSeals(reader, ctx);
            documentDTO.setTitle(getFileNameWithoutExt(originalFilename));
            documentDTO.setAuthor("未知");

            int pageCount = reader.getNumberOfPages();
            documentDTO.setPageCount(pageCount);

            for (int i = 0; i < pageCount; i++) {
                try {
                    pages.add(parsePage(reader, i, ctx));
                } catch (Exception e) {
                    log.error("解析第{}页失败: {}", i + 1, e.getMessage(), e);
                    PageDTO emptyPage = new PageDTO();
                    emptyPage.setPageIndex(i);
                    emptyPage.setSourcePageIndex(i);
                    emptyPage.setWidth(210.0);
                    emptyPage.setHeight(297.0);
                    emptyPage.setElements(new ArrayList<>());
                    pages.add(emptyPage);
                }
            }
        }

        documentDTO.setPages(pages);
        return documentDTO;
    }
    // ==================== 公开注释解析（供 OfdController 调用）====================

    /**
     * 从原始 OFD 字节流中解析所有页面的注释层
     * 直接操作 ZIP + DOM，复用已有私有方法，返回 Map<pageIndex, List<AnnotationDTO>>
     *
     * @param ofdBytes OFD 文件字节流
     * @return 各页注释列表，无注释的页不包含在 Map 中
     */
    public Map<Integer, List<AnnotationDTO>> parseAnnotations(byte[] ofdBytes) {
        if (ofdBytes == null || ofdBytes.length == 0) {
            return new HashMap<>();
        }

        Map<Integer, List<AnnotationDTO>> result = new ConcurrentHashMap<>();
        Path tempFile = null;

        try {
            tempFile = Files.createTempFile("parse-annot-", ".ofd");
            Files.write(tempFile, ofdBytes);

            try (ZipFile zipFile = new ZipFile(tempFile.toFile())) {
                Map<String, String> resourceMap = buildResourceMapForZip(zipFile);

                // 扫描 ZIP 内所有 Annotation.xml 路径，自动发现页码
                Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // 匹配类似 Doc_0/Annots/Page_0/Annotation.xml
                    if (!name.endsWith("Annotation.xml")) continue;

                    // 从路径中提取 pageIndex
                    Integer pageIndex = extractPageIndexFromAnnotPath(name);
                    if (pageIndex == null) continue;

                    byte[] bytes = readZipEntry(zipFile, name);
                    if (bytes == null || bytes.length == 0) continue;

                    List<AnnotationDTO> pageAnnotations =
                            parseAnnotationXmlToDTO(bytes, pageIndex, resourceMap);

                    if (!pageAnnotations.isEmpty()) {
                        result.put(pageIndex, pageAnnotations);
                        log.info("注释层解析: page={}, count={}, path={}",
                                pageIndex, pageAnnotations.size(), name);
                    }
                }
            }

        } catch (Exception e) {
            log.error("parseAnnotations 失败: {}", e.getMessage(), e);
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignore) {}
            }
        }

        return result;
    }

    /**
     * 从路径中提取 pageIndex
     * 支持格式：.../Page_0/... 或 .../Page_0001/...
     */
    private Integer extractPageIndexFromAnnotPath(String path) {
        if (path == null) return null;
        // 匹配 Page_数字
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("Page_(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(path);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 解析单个 Annotation.xml 的字节流，返回该页的 AnnotationDTO 列表
     * 只使用 AnnotationDTO 中真实存在的字段
     */
    /**
     * 为注释层图章解析建立 ResourceID → dataUrl 索引（与正文解析同源：Res/ + DocumentRes）
     */
    private Map<String, String> buildResourceMapForZip(ZipFile zip) {
        Map<String, String> map = new HashMap<>();
        try {
            String ofdXmlPath = findEntryIgnoreCase(zip, "OFD.xml");
            if (ofdXmlPath == null) return map;

            Document ofdDoc = parseXml(zip, ofdXmlPath);
            String docRoot = getFirstTextByLocalName(ofdDoc.getDocumentElement(), "DocRoot");
            if (!isNotBlank(docRoot)) return map;
            docRoot = normalizePath(docRoot);

            String documentXmlPath = resolvePath(docRoot, "Document.xml");
            if (zip.getEntry(documentXmlPath) == null) {
                documentXmlPath = findEntryEndsWithIgnoreCase(zip, "/" + docRoot + "/Document.xml");
                if (documentXmlPath == null) {
                    documentXmlPath = findEntryEndsWithIgnoreCase(zip, "Document.xml");
                }
            }
            if (documentXmlPath == null) return map;

            Document documentDoc = parseXml(zip, documentXmlPath);
            Element docRootElem = documentDoc.getDocumentElement();

            List<String> resXmlPaths = new ArrayList<>();
            for (String tag : new String[]{"PublicRes", "DocumentRes"}) {
                for (String v : getAllTextByLocalName(docRootElem, tag)) {
                    if (isNotBlank(v)) {
                        resXmlPaths.add(resolvePath(parentDir(documentXmlPath), normalizePath(v.trim())));
                    }
                }
            }

            for (String resXml0 : new HashSet<>(resXmlPaths)) {
                String resXml = resXml0;
                if (zip.getEntry(resXml) == null) {
                    String fallback = findEntryEndsWithIgnoreCase(zip, "/" + filename(resXml));
                    if (fallback == null) continue;
                    resXml = fallback;
                }
                Document resDoc = parseXml(zip, resXml);
                Element resRoot = resDoc.getDocumentElement();

                NodeList mediaNodes = resRoot.getElementsByTagNameNS("*", "MultiMedia");
                if (mediaNodes.getLength() == 0) {
                    mediaNodes = resRoot.getElementsByTagName("MultiMedia");
                }

                for (int i = 0; i < mediaNodes.getLength(); i++) {
                    Node n = mediaNodes.item(i);
                    if (!(n instanceof Element mm)) continue;

                    String id = firstNonBlank(mm.getAttribute("ID"),
                            mm.getAttribute("Id"), mm.getAttribute("id"));
                    if (!isNotBlank(id)) continue;

                    String mediaFile = getFirstTextByLocalName(mm, "MediaFile");
                    if (!isNotBlank(mediaFile)) continue;

                    mediaFile = normalizePath(mediaFile.trim());
                    String mediaPath = resolvePath(parentDir(resXml), mediaFile);

                    ZipEntry mediaEntry = zip.getEntry(mediaPath);
                    if (mediaEntry == null) {
                        String fallback = findEntryEndsWithIgnoreCase(zip, "/" + filename(mediaPath));
                        if (fallback == null) continue;
                        mediaPath = fallback;
                        mediaEntry = zip.getEntry(mediaPath);
                    }
                    if (mediaEntry == null) continue;

                    byte[] bytes = readAllBytes(zip, mediaEntry);
                    if (bytes == null || bytes.length == 0) continue;

                    String dataUrl = toBrowserSafeDataUrl(mediaPath, bytes);
                    if (isNotBlank(dataUrl)) {
                        map.putIfAbsent(id.trim(), dataUrl);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("注释图章资源索引失败: {}", e.getMessage());
        }
        return map;
    }

    private List<AnnotationDTO> parseAnnotationXmlToDTO(byte[] xmlBytes, int pageIndex,
                                                        Map<String, String> resourceMap) {
        List<AnnotationDTO> list = new ArrayList<>();
        try {
            Document doc = parseXmlBytes(xmlBytes);
            Element root = doc.getDocumentElement();

            // ── 1. ImageObject → STAMP ──
            NodeList imageNodes = root.getElementsByTagNameNS("*", "ImageObject");
            if (imageNodes.getLength() == 0) {
                imageNodes = root.getElementsByTagName("ImageObject");
            }
            for (int i = 0; i < imageNodes.getLength(); i++) {
                Node n = imageNodes.item(i);
                if (!(n instanceof Element el)) continue;

                String boundary = el.getAttribute("Boundary");
                List<Double> nums = extractNumbers(boundary);
                if (nums.size() < 4) continue;

                AnnotationDTO dto = new AnnotationDTO();
                dto.setId(UUID.randomUUID().toString());
                dto.setType("STAMP");
                dto.setPageIndex(pageIndex);
                dto.setX(nums.get(0));
                dto.setY(nums.get(1));
                dto.setWidth(nums.get(2));
                dto.setHeight(nums.get(3));
                dto.setOpacity(1.0);
                dto.setLineWidth(0.0);
                dto.setCreatedAt(System.currentTimeMillis());
                dto.setUpdatedAt(dto.getCreatedAt());

                String resourceId = firstNonBlank(
                        el.getAttribute("ResourceID"),
                        el.getAttribute("ResourceId"),
                        el.getAttribute("ResID")
                );
                if (isNotBlank(resourceId) && resourceMap != null) {
                    String dataUrl = resourceMap.get(resourceId.trim());
                    if (isNotBlank(dataUrl)) {
                        dto.setStampBase64(dataUrl);
                    }
                }

                list.add(dto);
            }

            // ── 2. PathObject → 高亮/下划线/手绘等 ──
            NodeList pathNodes = root.getElementsByTagNameNS("*", "PathObject");
            if (pathNodes.getLength() == 0) {
                pathNodes = root.getElementsByTagName("PathObject");
            }
            for (int i = 0; i < pathNodes.getLength(); i++) {
                Node n = pathNodes.item(i);
                if (!(n instanceof Element el)) continue;

                String boundary = el.getAttribute("Boundary");
                List<Double> nums = extractNumbers(boundary);
                if (nums.size() < 4) continue;

                AnnotationDTO dto = new AnnotationDTO();
                dto.setId(UUID.randomUUID().toString());
                dto.setPageIndex(pageIndex);
                dto.setX(nums.get(0));
                dto.setY(nums.get(1));
                dto.setWidth(nums.get(2));
                dto.setHeight(nums.get(3));
                dto.setOpacity(1.0);
                dto.setCreatedAt(System.currentTimeMillis());
                dto.setUpdatedAt(dto.getCreatedAt());

                // 读取填充色
                String fillColorStr = null;
                NodeList fillNodes = el.getElementsByTagNameNS("*", "FillColor");
                if (fillNodes.getLength() == 0) fillNodes = el.getElementsByTagName("FillColor");
                if (fillNodes.getLength() > 0 && fillNodes.item(0) instanceof Element fillEl) {
                    fillColorStr = fillEl.getAttribute("Value");
                }

                // 读取描边色
                String strokeColorStr = null;
                NodeList strokeNodes = el.getElementsByTagNameNS("*", "StrokeColor");
                if (strokeNodes.getLength() == 0) strokeNodes = el.getElementsByTagName("StrokeColor");
                if (strokeNodes.getLength() > 0 && strokeNodes.item(0) instanceof Element strokeEl) {
                    strokeColorStr = strokeEl.getAttribute("Value");
                }

                // 读取线宽
                String lineWidthStr = el.getAttribute("LineWidth");
                Double lineWidth = tryParseDouble(lineWidthStr);
                dto.setLineWidth(lineWidth != null && lineWidth > 0 ? lineWidth : 1.0);

                // 读取路径数据（存入 pathPoints）
                NodeList abbrevNodes = el.getElementsByTagNameNS("*", "AbbreviatedData");
                if (abbrevNodes.getLength() == 0) {
                    abbrevNodes = el.getElementsByTagName("AbbreviatedData");
                }
                if (abbrevNodes.getLength() > 0) {
                    String pathData = abbrevNodes.item(0).getTextContent();
                    if (isNotBlank(pathData)) {
                        dto.setPathPoints(pathData.trim());
                    }
                }
                String storedPoints = firstNonBlank(el.getAttribute("PathPoints"), el.getAttribute("pathPoints"));
                if (isNotBlank(storedPoints)) {
                    dto.setPathPoints(storedPoints.trim());
                } else if (isNotBlank(dto.getPathPoints()) && isLikelyOfdPathData(dto.getPathPoints())) {
                    String jsonPoints = ofdPathToJsonPointPairs(dto.getPathPoints(), nums.get(0), nums.get(1));
                    if (isNotBlank(jsonPoints)) dto.setPathPoints(jsonPoints);
                }

                // 颜色赋值
                dto.setColor(parseRgbString(fillColorStr, "#FFFF00"));
                dto.setStrokeColor(parseRgbString(strokeColorStr, null));

                // ── 类型推断（根据填充/描边/路径特征）──
                boolean hasFill   = isNotBlank(fillColorStr);
                boolean hasStroke = isNotBlank(strokeColorStr);
                String  pathData  = dto.getPathPoints();
                boolean isSimpleLine = pathData != null
                        && !pathData.contains("C")
                        && !pathData.contains("Q")
                        && !pathData.contains("A")
                        && pathData.contains("L");
                boolean isClosed  = pathData != null && pathData.contains("Z");
                double  h         = nums.get(3);
                double  w         = nums.get(2);

                String explicitType = firstNonBlank(el.getAttribute("Type"), el.getAttribute("type"));
                if (isNotBlank(explicitType)) {
                    dto.setType(explicitType.trim().toUpperCase(Locale.ROOT));
                } else if (hasFill && !hasStroke) {
                    // 仅填充：高亮（扁平矩形区域）
                    dto.setType(h < 5.0 ? "HIGHLIGHT" : "RECTANGLE");
                } else if (!hasFill && hasStroke) {
                    if (isSimpleLine) {
                        // 纯直线
                        if (h < 2.0) {
                            dto.setType("UNDERLINE");   // 极细 → 下划线
                        } else {
                            dto.setType("ARROW");        // 略粗 → 箭头
                        }
                    } else if (isClosed) {
                        // 闭合曲线
                        double ratio = (w > 0) ? h / w : 1.0;
                        dto.setType(Math.abs(ratio - 1.0) < 0.2 ? "CIRCLE" : "RECTANGLE");
                    } else {
                        dto.setType("FREEHAND");         // 开放曲线 → 手绘
                    }
                } else if (hasFill && hasStroke) {
                    dto.setType("RECTANGLE");            // 有边框有填充 → 矩形/便签
                } else {
                    dto.setType("FREEHAND");             // 兜底
                }

                list.add(dto);
            }

            // ── 3. TextObject → TEXTBOX ──
            NodeList textNodes = root.getElementsByTagNameNS("*", "TextObject");
            if (textNodes.getLength() == 0) {
                textNodes = root.getElementsByTagName("TextObject");
            }
            for (int i = 0; i < textNodes.getLength(); i++) {
                Node n = textNodes.item(i);
                if (!(n instanceof Element el)) continue;

                String boundary = el.getAttribute("Boundary");
                List<Double> nums = extractNumbers(boundary);
                if (nums.size() < 4) continue;

                AnnotationDTO dto = new AnnotationDTO();
                dto.setId(UUID.randomUUID().toString());
                dto.setType("TEXTBOX");
                dto.setPageIndex(pageIndex);
                dto.setX(nums.get(0));
                dto.setY(nums.get(1));
                dto.setWidth(nums.get(2));
                dto.setHeight(nums.get(3));
                dto.setOpacity(1.0);

                // 文本内容
                StringBuilder sb = new StringBuilder();
                NodeList tcNodes = el.getElementsByTagNameNS("*", "TextCode");
                if (tcNodes.getLength() == 0) tcNodes = el.getElementsByTagName("TextCode");
                for (int j = 0; j < tcNodes.getLength(); j++) {
                    String text = tcNodes.item(j).getTextContent();
                    if (isNotBlank(text)) sb.append(text);
                }
                dto.setContent(sb.toString());

                // 字号
                String sizeAttr = el.getAttribute("Size");
                Double fontSize = tryParseDouble(sizeAttr);
                dto.setFontSize(fontSize != null && fontSize > 0 ? fontSize : 12.0);

                // 字体颜色
                NodeList fillNodes = el.getElementsByTagNameNS("*", "FillColor");
                if (fillNodes.getLength() == 0) fillNodes = el.getElementsByTagName("FillColor");
                if (fillNodes.getLength() > 0 && fillNodes.item(0) instanceof Element fillEl) {
                    dto.setFontColor(parseRgbString(fillEl.getAttribute("Value"), "#000000"));
                } else {
                    dto.setFontColor("#000000");
                }

                dto.setCreatedAt(System.currentTimeMillis());
                dto.setUpdatedAt(dto.getCreatedAt());
                list.add(dto);
            }

        } catch (Exception e) {
            log.warn("parseAnnotationXmlToDTO 失败: pageIndex={}, msg={}", pageIndex, e.getMessage());
        }

        return list;
    }
    // ==================== 页面解析 ====================

    private PageDTO parsePage(OFDReader reader, int pageIndex, ParseContext ctx) {
        PageDTO pageDTO = new PageDTO();
        pageDTO.setPageIndex(pageIndex);
        pageDTO.setSourcePageIndex(pageIndex);
        int pageNum = pageIndex + 1;

        // 页面尺寸
        try {
            ST_Box box = reader.getPageSize(pageNum);
            if (box != null) {
                pageDTO.setWidth(box.getWidth());
                pageDTO.setHeight(box.getHeight());
            } else {
                pageDTO.setWidth(210.0);
                pageDTO.setHeight(297.0);
            }
        } catch (Exception e) {
            pageDTO.setWidth(210.0);
            pageDTO.setHeight(297.0);
        }

        List<ElementDTO> elements = new ArrayList<>();
        try {
            // 正文/模板 TextObject、PathObject 以 XML 为准（坐标），供 ofdrw 解析结果覆盖
            indexPageDomObjectsForPage(pageIndex, ctx);

            List<Object> ofdrwRoots = new ArrayList<>();
            Object pageObj = reader.getPage(pageNum);
            ofdrwRoots.add(pageObj);
            addIfNotNull(ofdrwRoots, invokeAny(pageObj,
                    "getLayer", "getLayers", "getContent", "getPageBlock"));

            List<Object> underlayRoots = new ArrayList<>();
            parseTemplateLayer(pageIndex, underlayRoots, ctx);
            addPageGraphicsDomRootsFromContentXml(pageIndex, underlayRoots, ctx);

            // 先解析 ofdrw 正文（Text/Image 坐标权威来源），再补模板/DOM 矢量底图
            Set<String> semanticSeen = new LinkedHashSet<>();
            List<ElementDTO> foreground = new ArrayList<>();
            collectFromRoots(ofdrwRoots, foreground, ctx, semanticSeen);

            List<ElementDTO> background = new ArrayList<>();
            collectPathsFromRoots(underlayRoots, background, ctx, semanticSeen);

            List<ElementDTO> supplementText = new ArrayList<>();
            collectSupplementTextFromRoots(underlayRoots, supplementText, ctx, semanticSeen);

            elements.addAll(background);
            elements.addAll(foreground);
            elements.addAll(supplementText);

            adjustCurrencyTextLayout(elements);

            // 注释层（印章等）
            parseAnnotations(pageNum, elements, ctx);

            // 电子签章（Signs/ → SignedValue 内嵌印章图）
            appendDigitalSealsForPage(pageIndex, elements, ctx);

        } catch (Exception e) {
            log.warn("解析第{}页内容失败: {}", pageNum, e.getMessage());
        }

        pageDTO.setElements(elements);
        log.info("第{}页元素数: {}", pageNum, elements.size());
        return pageDTO;
    }

    // ==================== 模板层（核心修复）====================

    /**
     * 直接从ZIP读取模板层XML，不依赖ofdrw API
     */
    private void parseTemplateLayer(int pageIndex, List<Object> roots, ParseContext ctx) {
        try {
            ZipFile zip = ctx.zipFile;

            // 1. 读取当前页的Content.xml，找TemplateID引用
            String pageContentPath = findPageContentPath(zip, pageIndex);
            if (pageContentPath == null) {
                log.warn("【模板】找不到第{}页Content.xml", pageIndex);
                return;
            }

            byte[] pageBytes = readZipEntry(zip, pageContentPath);
            if (pageBytes == null) return;

            List<String> templateIds = extractTemplateIdsFromPage(pageBytes);
            log.info("【模板】第{}页引用模板ID: {}", pageIndex, templateIds);
            if (templateIds.isEmpty()) return;

            // 2. 读取Document.xml，找TemplatePage定义（ID -> BaseLoc）
            Map<String, String> tplIdToPath = loadTemplatePagePaths(zip);
            log.info("【模板】Document.xml中模板路径映射: {}", tplIdToPath);

            // 3. 读取每个模板的Content.xml，解析为dom4j Element加入roots
            for (String tplId : templateIds) {
                String tplPath = tplIdToPath.get(tplId);
                if (tplPath == null) {
                    // 如果只有一个模板，直接用
                    if (tplIdToPath.size() == 1) {
                        tplPath = tplIdToPath.values().iterator().next();
                        log.info("【模板】ID={}未匹配，使用唯一模板: {}", tplId, tplPath);
                    } else {
                        log.warn("【模板】找不到ID={}对应的模板路径", tplId);
                        continue;
                    }
                }

                byte[] tplBytes = readZipEntry(zip, tplPath);
                if (tplBytes == null) {
                    log.warn("【模板】读取模板文件失败: {}", tplPath);
                    continue;
                }

                log.info("【模板】解析模板文件: {}", tplPath);
                parseTemplateContentXml(tplBytes, roots);
            }

        } catch (Exception e) {
            log.warn("parseTemplateLayer异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 找当前页的Content.xml路径
     */
    private String findPageContentPath(ZipFile zip, int pageIndex) {
        // 常见路径格式
        String[] candidates = {
                "Doc_0/Pages/Page_" + pageIndex + "/Content.xml",
                "Doc_0/Page_" + pageIndex + "/Content.xml",
                String.format("Doc_0/Pages/Page_%04d/Content.xml", pageIndex),
        };
        for (String c : candidates) {
            if (zip.getEntry(c) != null) return c;
        }
        // 模糊匹配
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            if (name.contains("Page_" + pageIndex) && name.endsWith("Content.xml")) {
                return name;
            }
        }
        return null;
    }

    /**
     * 从当前页 {@code Content.xml} 按文档顺序收集 {@code PathObject}（及 CT 变体）DOM，
     * 供 {@link #walk} → {@link #parseBlockFromDom} 解析。不含 {@code TextObject}（避免与 ofdrw 文本重复），
     * 也不含 {@code ImageObject}（由 ofdrw 解析，避免 PDF 转 OFD 等同 ResourceID 叠图）。
     * <p>常见“整页蓝底/底色”是首个 {@code PathObject} 矢量填充，不是图片位图；厂商 OFD 在 ofdrw 中常缺失这些节点。</p>
     */
    private void addPageGraphicsDomRootsFromContentXml(int pageIndex, List<Object> roots, ParseContext ctx) {
        if (ctx.zipFile == null) return;
        try {
            String pageContentPath = findPageContentPath(ctx.zipFile, pageIndex);
            if (pageContentPath == null) return;
            byte[] pageBytes = readZipEntry(ctx.zipFile, pageContentPath);
            if (pageBytes == null || pageBytes.length == 0) return;

            Document doc = parseXmlBytes(pageBytes);
            Element top = doc.getDocumentElement();
            if (top == null) return;
            List<Object> ordered = new ArrayList<>();
            collectPathAndImageDomInDocOrder(top, ordered);
            if (ordered.isEmpty()) return;
            for (Object o : ordered) {
                roots.add(o);
            }
            log.info("【正文XML】第{}页 {} 加入 Path 根节点: {} 个", pageIndex, pageContentPath, ordered.size());
        } catch (Exception e) {
            log.debug("addPageGraphicsDomRootsFromContentXml: {}", e.getMessage());
        }
    }

    /** 深度优先、与 Content.xml 中出现顺序一致，只收集 PathObject（及 CT_Path）。 */
    private void collectPathAndImageDomInDocOrder(Element el, List<Object> out) {
        String local = elementLocalName(el);
        if ("PathObject".equals(local) || "CT_Path".equals(local)) {
            out.add(el);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element) {
                collectPathAndImageDomInDocOrder((Element) c, out);
            }
        }
    }

    private String elementLocalName(Element el) {
        String ln = el.getLocalName();
        if (isNotBlank(ln)) return ln;
        String nn = el.getNodeName();
        if (nn != null && nn.contains(":")) {
            return nn.substring(nn.indexOf(':') + 1);
        }
        return nn;
    }

    /**
     * 索引当前页 Content.xml 及引用模板中的 TextObject / PathObject。
     */
    private void indexPageDomObjectsForPage(int pageIndex, ParseContext ctx) {
        ctx.textFillHexByObjectIdPage.clear();
        ctx.textDomByObjectIdPage.clear();
        ctx.pathDomByObjectIdPage.clear();
        if (ctx.zipFile == null) return;
        try {
            String pageContentPath = findPageContentPath(ctx.zipFile, pageIndex);
            if (pageContentPath == null) return;
            byte[] pageBytes = readZipEntry(ctx.zipFile, pageContentPath);
            if (pageBytes == null || pageBytes.length == 0) return;

            indexDomObjectsInXmlBytes(pageBytes, ctx);

            List<String> templateIds = extractTemplateIdsFromPage(pageBytes);
            if (!templateIds.isEmpty()) {
                Map<String, String> tplIdToPath = loadTemplatePagePaths(ctx.zipFile);
                for (String tplId : templateIds) {
                    String tplPath = tplIdToPath.get(tplId);
                    if (tplPath == null && tplIdToPath.size() == 1) {
                        tplPath = tplIdToPath.values().iterator().next();
                    }
                    if (tplPath == null) continue;
                    byte[] tplBytes = readZipEntry(ctx.zipFile, tplPath);
                    if (tplBytes != null) indexDomObjectsInXmlBytes(tplBytes, ctx);
                }
            }
        } catch (Exception e) {
            log.debug("indexPageDomObjectsForPage page={}: {}", pageIndex, e.getMessage());
        }
    }

    private void indexDomObjectsInXmlBytes(byte[] xmlBytes, ParseContext ctx) {
        try {
            Document doc = parseXmlBytes(xmlBytes);
            Element top = doc.getDocumentElement();
            if (top == null) return;
            indexDomObjectsRecursive(top, ctx);
        } catch (Exception e) {
            log.debug("indexDomObjectsInXmlBytes: {}", e.getMessage());
        }
    }

    private void indexDomObjectsRecursive(Element el, ParseContext ctx) {
        String local = elementLocalName(el);
        if ("TextObject".equals(local) || "CT_Text".equals(local)) {
            indexOneTextObjectFromDom(el, ctx);
        } else if ("PathObject".equals(local) || "CT_Path".equals(local)) {
            indexOnePathObjectFromDom(el, ctx);
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c instanceof Element child) indexDomObjectsRecursive(child, ctx);
        }
    }

    private void indexOnePathObjectFromDom(Element el, ParseContext ctx) {
        String oid = firstNonBlank(
                el.getAttribute("ID"), el.getAttribute("Id"), el.getAttribute("id"),
                el.getAttribute("ObjID"), el.getAttribute("ObjectID"));
        if (!isNotBlank(oid)) return;
        List<Double> nums = extractNumbers(el.getAttribute("Boundary"));
        if (nums.size() < 4) return;
        PathObjectDomInfo info = new PathObjectDomInfo();
        info.x = nums.get(0);
        info.y = nums.get(1);
        info.w = nums.get(2);
        info.h = nums.get(3);
        NodeList abbrevNodes = el.getElementsByTagNameNS("*", "AbbreviatedData");
        if (abbrevNodes.getLength() == 0) abbrevNodes = el.getElementsByTagName("AbbreviatedData");
        if (abbrevNodes.getLength() > 0) {
            String raw = abbrevNodes.item(0).getTextContent();
            if (isNotBlank(raw)) info.abbrevData = raw.trim();
        }
        info.ctmStr = firstNonBlank(el.getAttribute("CTM"), el.getAttribute("ctm"));
        ctx.pathDomByObjectIdPage.put(oid.trim(), info);
    }

    /** ofdrw Path 解析后，用 XML Boundary + AbbreviatedData 校正图案位置 */
    private void applyPathDomOverrides(ElementDTO dto, ParseContext ctx) {
        if (dto == null || ctx == null || !"PATH".equals(dto.getType())) return;
        if (!isNotBlank(dto.getXmlObjId())) return;
        PathObjectDomInfo dom = ctx.pathDomByObjectIdPage.get(dto.getXmlObjId().trim());
        if (dom == null || dom.x == null || dom.y == null) return;
        dto.setX(dom.x);
        dto.setY(dom.y);
        if (dom.w != null && dom.w > 0) dto.setWidth(dom.w);
        if (dom.h != null && dom.h > 0) dto.setHeight(dom.h);
        if (isNotBlank(dom.abbrevData)) {
            Matrix ctm = parseCTMString(dom.ctmStr);
            dto.setPathData(convertOfdPathToSvg(dom.abbrevData, dom.x, dom.y, ctm));
        }
    }

    private void indexOneTextObjectFromDom(Element el, ParseContext ctx) {
        String oid = firstNonBlank(
                el.getAttribute("ID"), el.getAttribute("Id"), el.getAttribute("id"),
                el.getAttribute("ObjID"), el.getAttribute("ObjectID"));
        if (!isNotBlank(oid)) return;

        List<Double> nums = extractNumbers(el.getAttribute("Boundary"));
        if (nums.size() < 4) return;

        TextCodeParseResult parsed = parseTextCodesFromDomElement(el);

        TextObjectDomInfo info = new TextObjectDomInfo();
        info.boundaryX = nums.get(0);
        info.boundaryY = nums.get(1);
        info.w = nums.get(2);
        info.h = nums.get(3);
        info.content = parsed.content;
        info.verticalLayout = parsed.verticalLayout ? Boolean.TRUE : null;

        Element firstTc = getFirstTextCodeElement(el);
        String dx = firstTc != null ? firstTc.getAttribute("DeltaX") : null;
        String dy = firstTc != null ? firstTc.getAttribute("DeltaY") : null;
        info.passwordGrid = isPasswordGridLayout(dx, dy, parsed.content);
        info.preferDomGeometry = parsed.verticalLayout || info.passwordGrid;

        double[] off = getFirstTextCodeOffsetFromDom(el);
        info.tcOffX = off != null ? off[0] : 0.0;
        info.tcOffY = off != null ? off[1] : 0.0;
        Double adv = resolveGlyphAdvanceMm(dx);
        String plainContent = parsed.content != null ? parsed.content.replace("\n", "").trim() : "";
        if (adv != null && adv > 0 && isCurrencyLikeContent(plainContent)) {
            info.glyphAdvanceMm = adv;
        }

        double fontSize = 12.0;
        Double s = tryParseDouble(el.getAttribute("Size"));
        if (s != null && s > 0) fontSize = s;
        Matrix ctm = parseCTMString(firstNonBlank(el.getAttribute("CTM"), el.getAttribute("ctm")));
        if (ctm != null) {
            double scaleY = Math.sqrt(ctm.b * ctm.b + ctm.d * ctm.d);
            if (Double.isFinite(scaleY) && scaleY > 0.0001) fontSize *= scaleY;
        }
        info.fontSize = fontSize;

        String hex = extractTextFillColorHexFromElement(el);
        if (isNotBlank(hex)) ctx.textFillHexByObjectIdPage.put(oid.trim(), hex);
        ctx.textDomByObjectIdPage.put(oid.trim(), info);
    }

    /** ofdrw 解析后，用 XML 索引覆盖坐标/换行/字号（仅竖排标签、密码区等需 XML 几何的场景） */
    private void applyTextDomOverrides(ElementDTO dto, ParseContext ctx) {
        if (dto == null || ctx == null || !"TEXT".equals(dto.getType())) return;
        if (!isNotBlank(dto.getXmlObjId())) return;
        TextObjectDomInfo dom = ctx.textDomByObjectIdPage.get(dto.getXmlObjId().trim());
        if (dom == null) return;
        if (Boolean.TRUE.equals(dom.preferDomGeometry)) {
            double bx = dom.boundaryX != null ? dom.boundaryX : 0;
            double by = dom.boundaryY != null ? dom.boundaryY : 0;
            double tcx = dom.tcOffX != null ? dom.tcOffX : 0;
            double tcy = dom.tcOffY != null ? dom.tcOffY : 0;
            double fs = dom.fontSize != null && dom.fontSize > 0 ? dom.fontSize : 3.175;
            boolean vert = Boolean.TRUE.equals(dom.verticalLayout);
            boolean pwd = Boolean.TRUE.equals(dom.passwordGrid);
            dto.setX(applyTextOriginX(bx, tcx, vert, pwd));
            dto.setY(computeKonvaTextY(by, tcy, fs, vert, pwd));
            if (dom.w != null && dom.w > 0) dto.setWidth(dom.w);
            if (dom.h != null && dom.h > 0) dto.setHeight(dom.h);
            if (isNotBlank(dom.content)) dto.setContent(dom.content);
            if (dom.verticalLayout != null) dto.setVerticalLayout(dom.verticalLayout);
            if (dom.passwordGrid != null) dto.setPasswordGrid(dom.passwordGrid);
            if (dom.fontSize != null && dom.fontSize > 0) dto.setFontSize(dom.fontSize);
        } else {
            double bx = dom.boundaryX != null ? dom.boundaryX : (dto.getX() != null ? dto.getX() : 0.0);
            double by = dom.boundaryY != null ? dom.boundaryY : (dto.getY() != null ? dto.getY() : 0.0);
            double tcx = dom.tcOffX != null ? dom.tcOffX : 0;
            double tcy = dom.tcOffY != null ? dom.tcOffY : 0;
            double fs = dto.getFontSize() != null && dto.getFontSize() > 0 ? dto.getFontSize() : 3.175;
            boolean vert = Boolean.TRUE.equals(dto.getVerticalLayout());
            boolean pwd = Boolean.TRUE.equals(dom.passwordGrid);
            String domPlain = isNotBlank(dom.content) ? dom.content.replace("\n", "").trim() : "";
            boolean currency = isCurrencyLikeContent(
                    isNotBlank(domPlain) ? domPlain : dto.getContent());
            if (currency) {
                dto.setX(bx + tcx);
                dto.setY(computeKonvaTextY(by, tcy, fs, false, false));
                if (isNotBlank(domPlain)) dto.setContent(domPlain);
                dto.setPasswordGrid(null);
            } else {
                dto.setX(pwd ? applyTextOriginX(bx, tcx, false, true) : bx);
                dto.setY(computeKonvaTextY(by, tcy, fs, vert, pwd));
                if (pwd) dto.setPasswordGrid(true);
                if (isNotBlank(dom.content) && dom.content.contains("\n")
                        && (dto.getContent() == null || !dto.getContent().contains("\n"))) {
                    dto.setContent(dom.content);
                }
            }
        }
        String hex = ctx.textFillHexByObjectIdPage.get(dto.getXmlObjId().trim());
        if (isNotBlank(hex)) dto.setColor(hex);
        if (dom.glyphAdvanceMm != null && dom.glyphAdvanceMm > 0
                && isCurrencyLikeContent(dto.getContent())) {
            dto.setGlyphAdvanceMm(dom.glyphAdvanceMm);
        }
    }

    /** 金额行：¥ 开头或末尾带 ¥+数字（如 (小写)¥526.00） */
    private boolean isCurrencyLikeContent(String text) {
        if (!isNotBlank(text)) return false;
        String t = text.trim();
        if (t.matches("^[¥￥\\u00a5\\uffe5][\\d.,]+$")) return true;
        return t.matches(".*[¥￥\\u00a5\\uffe5][\\d.,]+$") && t.length() <= 24;
    }

    private boolean isOnlyCurrencySymbol(String text) {
        if (!isNotBlank(text)) return false;
        String t = text.trim();
        return "¥".equals(t) || "￥".equals(t) || "\u00a5".equals(t) || "\uffe5".equals(t);
    }

    private boolean isPlainAmountText(String text) {
        if (!isNotBlank(text)) return false;
        return text.trim().matches("^[\\d.,]+$");
    }

    /**
     * 发票金额常见为「¥」与数字分两个 TextObject。合并为单条供前端分段渲染，避免 Web 字体 ¥ 过宽叠字。
     */
    private void adjustCurrencyTextLayout(List<ElementDTO> elements) {
        if (elements == null || elements.isEmpty()) return;
        List<ElementDTO> remove = new ArrayList<>();
        for (ElementDTO sym : elements) {
            if (!"TEXT".equals(sym.getType()) || !isOnlyCurrencySymbol(sym.getContent())) continue;
            Double symY = sym.getY();
            Double symX = sym.getX();
            double fs = sym.getFontSize() != null && sym.getFontSize() > 0 ? sym.getFontSize() : 3.175;
            if (symY == null || symX == null) continue;
            ElementDTO best = null;
            double bestDx = Double.MAX_VALUE;
            for (ElementDTO amt : elements) {
                if (amt == sym || remove.contains(amt) || !"TEXT".equals(amt.getType())) continue;
                if (!isPlainAmountText(amt.getContent())) continue;
                Double ay = amt.getY();
                Double ax = amt.getX();
                if (ay == null || ax == null) continue;
                if (Math.abs(ay - symY) > fs * 0.85) continue;
                double dx = ax - symX;
                if (dx >= -0.2 && dx < fs * 1.2 && dx < bestDx) {
                    bestDx = dx;
                    best = amt;
                }
            }
            if (best != null) {
                sym.setContent(sym.getContent().trim() + best.getContent().trim());
                remove.add(best);
            }
        }
        elements.removeAll(remove);
    }

    private static class TextCodeParseResult {
        final String content;
        final boolean verticalLayout;
        TextCodeParseResult(String content, boolean verticalLayout) {
            this.content = content;
            this.verticalLayout = verticalLayout;
        }
    }

    /** 解析 TextObject 下多个 TextCode：竖排单字 / 密码区多行 / 横向拼接 */
    private TextCodeParseResult parseTextCodesFromDomElement(Element textObj) {
        StringBuilder sb = new StringBuilder();
        boolean verticalLayout = false;
        NodeList tcNodes = textObj.getElementsByTagNameNS("*", "TextCode");
        if (tcNodes.getLength() == 0) tcNodes = textObj.getElementsByTagName("TextCode");
        int tcCount = tcNodes.getLength();
        Double prevY = null;

        for (int i = 0; i < tcCount; i++) {
            Node n = tcNodes.item(i);
            if (!(n instanceof Element tc)) continue;
            String text = tc.getTextContent();
            if (!isNotBlank(text)) continue;
            String trimmed = text.trim();

            Double tcY = tryParseDouble(firstNonBlank(tc.getAttribute("Y"), tc.getAttribute("y")));

            String deltaXAttr = tc.getAttribute("DeltaX");
            String deltaYAttr = tc.getAttribute("DeltaY");
            if (isVerticalCharLayout(deltaXAttr, deltaYAttr, trimmed)) {
                verticalLayout = true;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                for (int k = 0; k < trimmed.length(); k++) {
                    if (k > 0) sb.append('\n');
                    sb.append(trimmed.charAt(k));
                }
            } else {
                if (sb.length() > 0 && shouldStartNewTextLine(tcCount, prevY, tcY)) {
                    sb.append('\n');
                }
                sb.append(applyGridLineBreaks(trimmed, deltaXAttr, deltaYAttr));
                if (tcY != null) prevY = tcY;
            }
        }
        return new TextCodeParseResult(sb.toString(), verticalLayout);
    }

    private TextCodeParseResult parseTextCodesFromOfdrw(Object textObj) {
        StringBuilder sb = new StringBuilder();
        boolean verticalLayout = false;
        Double prevY = null;
        Object textCodes = invokeAny(textObj, "getTextCode", "getTextCodes");
        List<?> tcList = textCodes instanceof List<?> ? (List<?>) textCodes : List.of();
        int tcCount = tcList.size();

        for (Object tc : tcList) {
            Object content = invokeAny(tc, "getContent", "getText", "getValue");
            if (content == null) continue;
            String text = String.valueOf(content);
            if (!isNotBlank(text)) continue;
            String trimmed = text.trim();

            Double tcY = invokeDoubleAny(tc, "getY", "gety");

            String deltaXAttr = invokeStringAny(tc, "getDeltaX");
            String deltaYAttr = invokeStringAny(tc, "getDeltaY");
            if (isVerticalCharLayout(deltaXAttr, deltaYAttr, trimmed)) {
                verticalLayout = true;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                for (int k = 0; k < trimmed.length(); k++) {
                    if (k > 0) sb.append('\n');
                    sb.append(trimmed.charAt(k));
                }
            } else {
                if (sb.length() > 0 && shouldStartNewTextLine(tcCount, prevY, tcY)) {
                    sb.append('\n');
                }
                sb.append(applyGridLineBreaks(trimmed, deltaXAttr, deltaYAttr));
                if (tcY != null) prevY = tcY;
            }
        }
        return new TextCodeParseResult(sb.toString(), verticalLayout);
    }

    /** 竖排单字：仅有 DeltaY、无 DeltaX/g 语法，且非密码区网格排版 */
    private boolean isVerticalCharLayout(String deltaX, String deltaY, String text) {
        if (!isNotBlank(text) || text.length() <= 1) return false;
        if (hasDeltaGNotation(deltaX, deltaY)) return false;
        List<Double> dy = extractNumbers(deltaY);
        List<Double> dx = extractNumbers(deltaX);
        boolean hasDy = dy.stream().anyMatch(v -> Math.abs(v) > 0.01);
        boolean hasDx = dx.stream().anyMatch(v -> Math.abs(v) > 0.01);
        return hasDy && !hasDx;
    }

    /** TextCode DeltaX 正数均值（mm），用于金额等逐字排版 */
    private Double averagePositiveDeltaX(String deltaX) {
        if (!isNotBlank(deltaX) || deltaX.contains("g ")) return null;
        List<Double> nums = extractNumbers(deltaX);
        if (nums.isEmpty()) return null;
        double sum = 0;
        int n = 0;
        for (Double v : nums) {
            if (v != null && v > 0.05 && v < 30) {
                sum += v;
                n++;
            }
        }
        return n > 0 ? sum / n : null;
    }

    /** 从 {@code g N advance ...} 语法提取首字间距（发票金额常见） */
    private Double firstPositiveAdvanceFromDeltaG(String deltaX) {
        if (!isNotBlank(deltaX) || !deltaX.contains("g ")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)g\\s+\\d+\\s+([+-]?\\d+(?:\\.\\d+)?)")
                .matcher(deltaX.trim());
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (v > 0.05 && v < 30) return v;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private Double resolveGlyphAdvanceMm(String deltaX) {
        Double fromG = firstPositiveAdvanceFromDeltaG(deltaX);
        if (fromG != null) return fromG;
        return averagePositiveDeltaX(deltaX);
    }

    private boolean hasDeltaGNotation(String deltaX, String deltaY) {
        return (isNotBlank(deltaX) && deltaX.contains("g "))
                || (isNotBlank(deltaY) && deltaY.contains("g "));
    }

    /** OFD {@code g N} 语法：单个 TextCode 内按 N 字换行（密码区等） */
    private Integer detectCharsPerLineFromDeltaG(String deltaAttr) {
        if (!isNotBlank(deltaAttr)) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)g\\s+(\\d+)").matcher(deltaAttr.trim());
        if (!m.find()) return null;
        try {
            int n = Integer.parseInt(m.group(1));
            return n > 1 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String applyGridLineBreaks(String text, String deltaX, String deltaY) {
        if (!isPasswordGridLayout(deltaX, deltaY, text)) return text;
        Integer cpl = detectCharsPerLineFromDeltaG(deltaX);
        if (cpl == null) cpl = detectCharsPerLineFromDeltaG(deltaY);
        if (cpl == null || text.length() <= cpl) return text;
        int lineCount = Math.max(countDeltaGGroups(deltaX), countDeltaGGroups(deltaY));
        if (lineCount >= 2) {
            StringBuilder sb = new StringBuilder(text.length() + lineCount);
            for (int line = 0; line < lineCount; line++) {
                int start = line * cpl;
                if (start >= text.length()) break;
                if (line > 0) sb.append('\n');
                int end = (line == lineCount - 1) ? text.length() : Math.min(start + cpl, text.length());
                sb.append(text, start, end);
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(text.length() + text.length() / cpl);
        for (int i = 0; i < text.length(); i++) {
            if (i > 0 && i % cpl == 0) sb.append('\n');
            sb.append(text.charAt(i));
        }
        return sb.toString();
    }

    /** 密码区网格：DeltaX 多组 {@code g N} 重复 + 长文本；单行金额 {@code g 8} 不算密码区 */
    private boolean isPasswordGridLayout(String deltaX, String deltaY, String text) {
        if (!isNotBlank(text) || !hasDeltaGNotation(deltaX, deltaY)) return false;
        String plain = text.replace("\n", "").trim();
        if (isCurrencyLikeContent(plain)) return false;
        Integer cpl = detectCharsPerLineFromDeltaG(deltaX);
        if (cpl == null) cpl = detectCharsPerLineFromDeltaG(deltaY);
        if (cpl == null || cpl < 8 || plain.length() <= cpl) return false;
        boolean hasLineReset = isNotBlank(deltaX) && deltaX.matches(".*-\\d+(?:\\.\\d+)?.*");
        int gx = countDeltaGGroups(deltaX);
        if (hasLineReset && gx >= 2) return true;
        return cpl >= 20 && plain.length() >= cpl * 2;
    }

    private int countDeltaGGroups(String delta) {
        if (!isNotBlank(delta)) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)g\\s+").matcher(delta);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /** 竖排/密码区叠加 TextCode.X；普通横向文本 ofdrw Boundary 已含水平定位 */
    private double applyTextOriginX(double boundaryX, double tcOffX, boolean vertical, boolean passwordGrid) {
        if (vertical || passwordGrid) return boundaryX + tcOffX;
        return boundaryX;
    }

    /**
     * OFD TextCode.Y 为基线参考点，Konva Text 的 y 为顶边：横向文本减去约 0.78×字号。
     * 密码区从 Boundary 顶边垂直居中（前端配合 verticalAlign）。
     */
    private double computeKonvaTextY(double boundaryY, double tcOffY, double fontSizeMm,
                                     boolean vertical, boolean passwordGrid) {
        if (passwordGrid) return boundaryY;
        if (vertical) return boundaryY + tcOffY;
        if (tcOffY > 0.01) {
            return boundaryY + tcOffY - fontSizeMm * 0.78;
        }
        return boundaryY;
    }

    /** TextCode 偏移：横向只调 Y，竖排/密码区调 X+Y */
    private void applyTextCodeOriginOffset(ElementDTO dto, Element textObj) {
        if (dto == null || textObj == null) return;
        double[] off = getFirstTextCodeOffsetFromDom(textObj);
        if (off == null) return;
        Element tc = getFirstTextCodeElement(textObj);
        String dx = tc != null ? tc.getAttribute("DeltaX") : null;
        String dy = tc != null ? tc.getAttribute("DeltaY") : null;
        boolean passwordGrid = isPasswordGridLayout(dx, dy, dto.getContent());
        boolean vertical = Boolean.TRUE.equals(dto.getVerticalLayout());
        double fs = dto.getFontSize() != null && dto.getFontSize() > 0 ? dto.getFontSize() : 3.175;
        double baseX = dto.getX() == null ? 0 : dto.getX();
        double baseY = dto.getY() == null ? 0 : dto.getY();
        dto.setX(applyTextOriginX(baseX, off[0], vertical, passwordGrid));
        dto.setY(computeKonvaTextY(baseY, off[1], fs, vertical, passwordGrid));
        if (passwordGrid) dto.setPasswordGrid(true);
    }

    private double[] getFirstTextCodeOffsetFromDom(Element textObj) {
        Element tc = getFirstTextCodeElement(textObj);
        if (tc == null) return null;
        Double tx = tryParseDouble(firstNonBlank(tc.getAttribute("X"), tc.getAttribute("x")));
        Double ty = tryParseDouble(firstNonBlank(tc.getAttribute("Y"), tc.getAttribute("y")));
        if (tx == null && ty == null) return null;
        return new double[]{ tx == null ? 0.0 : tx, ty == null ? 0.0 : ty };
    }

    private Element getFirstTextCodeElement(Element textObj) {
        if (textObj == null) return null;
        NodeList tcNodes = textObj.getElementsByTagNameNS("*", "TextCode");
        if (tcNodes.getLength() == 0) tcNodes = textObj.getElementsByTagName("TextCode");
        if (tcNodes.getLength() == 0) return null;
        Node n = tcNodes.item(0);
        return (n instanceof Element el) ? el : null;
    }

    /** 多个 TextCode 时默认按行拼接（密码区）；Y 坐标差显著时也换行 */
    private boolean shouldStartNewTextLine(int tcCount, Double prevY, Double tcY) {
        if (tcCount <= 1) return false;
        if (prevY != null && tcY != null) return Math.abs(tcY - prevY) > 0.2;
        return true;
    }

    /** OFD {@code FillColor}：{@code Value}、子结点 {@code Color}、渐变 {@code Segment} 中的颜色。 */
    private String paintColorHexFromElement(Element paintEl) {
        if (paintEl == null) return null;
        String vAttr = paintEl.getAttribute("Value");
        if (isNotBlank(vAttr)) {
            String h = parseRgbString(vAttr, null);
            if (isNotBlank(h)) return h;
        }
        NodeList colorKids = paintEl.getElementsByTagNameNS("*", "Color");
        if (colorKids.getLength() == 0) colorKids = paintEl.getElementsByTagName("Color");
        for (int i = 0; i < colorKids.getLength(); i++) {
            if (colorKids.item(i) instanceof Element ce) {
                String h = parseRgbString(ce.getAttribute("Value"), null);
                if (isNotBlank(h)) return h;
            }
        }
        NodeList segments = paintEl.getElementsByTagNameNS("*", "Segment");
        if (segments.getLength() == 0) segments = paintEl.getElementsByTagName("Segment");
        for (int i = 0; i < segments.getLength(); i++) {
            if (!(segments.item(i) instanceof Element seg)) continue;
            NodeList cols = seg.getElementsByTagNameNS("*", "Color");
            if (cols.getLength() == 0) cols = seg.getElementsByTagName("Color");
            if (cols.getLength() > 0 && cols.item(0) instanceof Element ce) {
                String h = parseRgbString(ce.getAttribute("Value"), null);
                if (isNotBlank(h)) return h;
            }
        }
        return null;
    }

    /** 文本对象上的填充色：优先 FillColor 子树，其次属性 {@code FillColor}，再次 DefaultAppearance。 */
    private String extractTextFillColorHexFromElement(Element el) {
        if (el == null) return null;
        NodeList fillNodes = el.getElementsByTagNameNS("*", "FillColor");
        if (fillNodes.getLength() == 0) fillNodes = el.getElementsByTagName("FillColor");
        if (fillNodes.getLength() > 0 && fillNodes.item(0) instanceof Element fillEl) {
            String h = paintColorHexFromElement(fillEl);
            if (isNotBlank(h)) return h;
        }
        String fillAttr = el.getAttribute("FillColor");
        if (isNotBlank(fillAttr)) {
            String p = parseRgbString(fillAttr, null);
            if (isNotBlank(p)) return p;
        }
        NodeList daNodes = el.getElementsByTagNameNS("*", "DefaultAppearance");
        if (daNodes.getLength() == 0) daNodes = el.getElementsByTagName("DefaultAppearance");
        if (daNodes.getLength() > 0 && daNodes.item(0) instanceof Element daEl) {
            NodeList daFill = daEl.getElementsByTagNameNS("*", "FillColor");
            if (daFill.getLength() == 0) daFill = daEl.getElementsByTagName("FillColor");
            if (daFill.getLength() > 0 && daFill.item(0) instanceof Element daFillEl) {
                String h = paintColorHexFromElement(daFillEl);
                if (isNotBlank(h)) return h;
            }
        }
        return null;
    }

    /**
     * 从页面Content.xml里提取所有TemplateID属性值
     */
    private List<String> extractTemplateIdsFromPage(byte[] xmlBytes) {
        List<String> ids = new ArrayList<>();
        try {
            Document doc = parseXmlBytes(xmlBytes);
            // 找 <Template TemplateID="x"/> 节点
            NodeList nodes = doc.getElementsByTagNameNS("*", "Template");
            if (nodes.getLength() == 0) nodes = doc.getElementsByTagName("Template");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node n = nodes.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                String id = firstNonBlank(
                        el.getAttribute("TemplateID"),
                        el.getAttribute("templateID"),
                        el.getAttribute("ID")
                );
                if (isNotBlank(id)) ids.add(id.trim());
            }
        } catch (Exception e) {
            log.warn("extractTemplateIdsFromPage异常: {}", e.getMessage());
        }
        return ids;
    }

    /**
     * 从Document.xml读取模板页定义，返回 ID -> Content.xml路径 的映射
     */
    private Map<String, String> loadTemplatePagePaths(ZipFile zip) {
        Map<String, String> result = new HashMap<>();
        try {
            // 找Document.xml
            String docXmlPath = null;
            String[] docCandidates = {"Doc_0/Document.xml", "doc_0/Document.xml"};
            for (String c : docCandidates) {
                if (zip.getEntry(c) != null) {
                    docXmlPath = c;
                    break;
                }
            }
            if (docXmlPath == null) {
                // 模糊匹配
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().endsWith("Document.xml")) {
                        docXmlPath = e.getName();
                        break;
                    }
                }
            }
            if (docXmlPath == null) {
                log.warn("【模板】找不到Document.xml");
                return result;
            }

            byte[] docBytes = readZipEntry(zip, docXmlPath);
            if (docBytes == null) return result;

            Document doc = parseXmlBytes(docBytes);
            String docDir = parentDir(docXmlPath); // 如 "Doc_0"

            // 找 <TemplatePage ID="x" BaseLoc="Tpls/Tpl_0/Content.xml"/>
            NodeList nodes = doc.getElementsByTagNameNS("*", "TemplatePage");
            if (nodes.getLength() == 0) nodes = doc.getElementsByTagName("TemplatePage");

            for (int i = 0; i < nodes.getLength(); i++) {
                Node n = nodes.item(i);
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                String id = el.getAttribute("ID");
                String baseLoc = el.getAttribute("BaseLoc");
                if (!isNotBlank(id) || !isNotBlank(baseLoc)) continue;

                // 拼出完整路径
                String fullPath = resolvePath(docDir, normalizePath(baseLoc.trim()));
                // 如果BaseLoc直接是Content.xml，补全
                if (!fullPath.endsWith(".xml")) {
                    fullPath = fullPath + "/Content.xml";
                }
                result.put(id.trim(), fullPath);
                log.info("【模板】TemplatePage ID={} -> {}", id.trim(), fullPath);
            }

            // 如果Document.xml里没有，扫描ZIP里所有Tpl*/Content.xml
            if (result.isEmpty()) {
                log.info("【模板】Document.xml未找到TemplatePage，扫描ZIP...");
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    String name = e.getName();
                    if ((name.contains("/Tpl") || name.contains("/tpl"))
                            && name.endsWith("Content.xml")) {
                        // 用序号作为临时ID
                        String tmpId = String.valueOf(result.size());
                        result.put(tmpId, name);
                        log.info("【模板】扫描发现模板文件: {}", name);
                    }
                }
            }

        } catch (Exception e) {
            log.warn("loadTemplatePagePaths异常: {}", e.getMessage(), e);
        }
        return result;
    }

    /**
     * 解析模板Content.xml，把Layer里的元素节点加入roots
     * 使用W3C DOM直接操作，与walk()的dom4j兼容问题通过转换解决
     */
    private void parseTemplateContentXml(byte[] xmlBytes, List<Object> roots) {
        try {
            Document doc = parseXmlBytes(xmlBytes);
            Element root = doc.getDocumentElement();

            // 找所有Layer节点
            NodeList layers = root.getElementsByTagNameNS("*", "Layer");
            if (layers.getLength() == 0) layers = root.getElementsByTagName("Layer");

            log.info("【模板XML】找到Layer {} 个", layers.getLength());

            if (layers.getLength() > 0) {
                for (int i = 0; i < layers.getLength(); i++) {
                    Node layer = layers.item(i);
                    // 把Layer下的直接子元素加入roots
                    NodeList children = layer.getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        Node child = children.item(j);
                        if (child instanceof Element) {
                            roots.add(child);
                            log.debug("【模板XML】加入元素: {}", child.getLocalName());
                        }
                    }
                }
            } else {
                // 没有Layer，直接加根节点的子元素
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child instanceof Element) roots.add(child);
                }
            }

        } catch (Exception e) {
            log.warn("parseTemplateContentXml异常: {}", e.getMessage(), e);
        }
    }

    // ==================== 注释层 ====================

    private void parseAnnotations(int pageNum, List<ElementDTO> elements, ParseContext ctx) {
        try {
            String annotPath = String.format("Doc_0/Annots/Page_%d/Annotation.xml", pageNum - 1);
            String[] candidates = {
                    annotPath,
                    annotPath.replace("Doc_0", "doc_0"),
                    String.format("Doc_0/Annots/Page_%04d/Annotation.xml", pageNum - 1)
            };

            for (String candidate : candidates) {
                ZipEntry entry = ctx.zipFile.getEntry(candidate);
                if (entry == null) continue;

                byte[] bytes = readZipEntry(ctx.zipFile, candidate);
                if (bytes == null) continue;

                Document annotDoc = parseXmlBytes(bytes);
                parseAnnotXml(annotDoc.getDocumentElement(), elements, ctx);
                log.info("第{}页注释层解析完成: {}", pageNum, candidate);
                break;
            }
        } catch (Exception e) {
            log.debug("parseAnnotations失败: {}", e.getMessage());
        }
    }

    private void parseAnnotXml(Element root, List<ElementDTO> elements, ParseContext ctx) {
        // 解析 ImageObject（印章图片）
        log.info("【注释XML】ImageObject数量={}, PathObject数量={}",
                root.getElementsByTagNameNS("*", "ImageObject").getLength() +
                        root.getElementsByTagName("ImageObject").getLength(),
                root.getElementsByTagNameNS("*", "PathObject").getLength() +
                        root.getElementsByTagName("PathObject").getLength()
        );
        NodeList images = root.getElementsByTagNameNS("*", "ImageObject");
        if (images.getLength() == 0) images = root.getElementsByTagName("ImageObject");

        for (int i = 0; i < images.getLength(); i++) {
            Node n = images.item(i);
            if (!(n instanceof Element)) continue;
            Element imgElem = (Element) n;

            String boundary = imgElem.getAttribute("Boundary");
            if (!isNotBlank(boundary)) continue;
            List<Double> nums = extractNumbers(boundary);
            if (nums.size() < 4) continue;

            ElementDTO dto = new ElementDTO();
            dto.setId(UUID.randomUUID().toString());
            dto.setType("IMAGE");
            dto.setX(nums.get(0));
            dto.setY(nums.get(1));
            dto.setWidth(nums.get(2));
            dto.setHeight(nums.get(3));
            dto.setRotation(0.0);
            dto.setScaleX(1.0);
            dto.setScaleY(1.0);
            dto.setIsDirty(false);

            String resourceId = firstNonBlank(
                    imgElem.getAttribute("ResourceID"),
                    imgElem.getAttribute("ResourceId"),
                    imgElem.getAttribute("ResID")
            );
            dto.setResourceId(resourceId);

            if (isNotBlank(resourceId)) {
                String dataUrl = ctx.resourceDataUrlById.get(resourceId.trim());
                log.info("【注释图片】resourceId={}, dataUrl命中={}, key列表大小={}, dataUrl前缀={}",
                        resourceId,
                        isNotBlank(dataUrl),
                        ctx.resourceDataUrlById.size(),
                        isNotBlank(dataUrl) ? dataUrl.substring(0, Math.min(30, dataUrl.length())) : "NULL"
                );
                if (isNotBlank(dataUrl)) {
                    dto.setImageBase64(dataUrl);
                    dto.setImageData(dataUrl);
                }
                ctx.annotResourceIds.add(resourceId.trim());
            }

            dto.setOriginalX(dto.getX());
            dto.setOriginalY(dto.getY());
            dto.setOriginalWidth(dto.getWidth());
            dto.setOriginalHeight(dto.getHeight());
            dto.setOriginalRotation(0.0);
            elements.add(dto);
        }
        NodeList paths = root.getElementsByTagNameNS("*", "PathObject");
        if (paths.getLength() == 0) paths = root.getElementsByTagName("PathObject");

        for (int i = 0; i < paths.getLength(); i++) {
            Node n = paths.item(i);
            if (!(n instanceof Element)) continue;
            Element pathElem = (Element) n;

            String boundary = pathElem.getAttribute("Boundary");
            if (!isNotBlank(boundary)) continue;
            List<Double> nums = extractNumbers(boundary);
            if (nums.size() < 4) continue;

            NodeList abbrevNodes = pathElem.getElementsByTagNameNS("*", "AbbreviatedData");
            if (abbrevNodes.getLength() == 0)
                abbrevNodes = pathElem.getElementsByTagName("AbbreviatedData");
            if (abbrevNodes.getLength() == 0) continue;

            String pathData = abbrevNodes.item(0).getTextContent();
            if (!isNotBlank(pathData)) continue;

            ElementDTO dto = new ElementDTO();
            dto.setId(UUID.randomUUID().toString());
            dto.setType("PATH");
            dto.setX(nums.get(0));
            dto.setY(nums.get(1));
            dto.setWidth(nums.get(2));
            dto.setHeight(nums.get(3));
            dto.setPathData(pathData.trim());
            dto.setRotation(0.0);
            dto.setScaleX(1.0);
            dto.setScaleY(1.0);
            dto.setIsDirty(false);

            NodeList fillNodes = pathElem.getElementsByTagNameNS("*", "FillColor");
            if (fillNodes.getLength() == 0) fillNodes = pathElem.getElementsByTagName("FillColor");
            if (fillNodes.getLength() > 0 && fillNodes.item(0) instanceof Element) {
                dto.setFillColor(parseRgbString(((Element) fillNodes.item(0)).getAttribute("Value"), null));
            }

            NodeList strokeNodes = pathElem.getElementsByTagNameNS("*", "StrokeColor");
            if (strokeNodes.getLength() == 0) strokeNodes = pathElem.getElementsByTagName("StrokeColor");
            if (strokeNodes.getLength() > 0 && strokeNodes.item(0) instanceof Element) {
                dto.setStrokeColor(parseRgbString(((Element) strokeNodes.item(0)).getAttribute("Value"), "#222222"));
            }

            dto.setOriginalX(dto.getX());
            dto.setOriginalY(dto.getY());
            dto.setOriginalWidth(dto.getWidth());
            dto.setOriginalHeight(dto.getHeight());
            dto.setOriginalRotation(0.0);
            elements.add(dto);
        }
    }

    // ==================== 元素遍历 ====================

    private void collectFromRoots(List<Object> roots, List<ElementDTO> elements, ParseContext ctx) {
        collectFromRoots(roots, elements, ctx, new LinkedHashSet<>());
    }

    private void collectFromRoots(List<Object> roots, List<ElementDTO> elements,
                                  ParseContext ctx, Set<String> semanticSeen) {
        Set<Integer> visited = new HashSet<>();
        for (Object root : roots) {
            walk(root, elements, visited, semanticSeen, 0, ctx);
        }
    }

    /** 仅从模板/DOM 收集 PathObject，避免模板 Text 与 ofdrw 重复叠层 */
    private void collectPathsFromRoots(List<Object> roots, List<ElementDTO> elements,
                                       ParseContext ctx, Set<String> semanticSeen) {
        Set<Integer> visited = new HashSet<>();
        for (Object root : roots) {
            walkPathsOnly(root, elements, visited, semanticSeen, 0, ctx);
        }
    }

    private void walkPathsOnly(Object node, List<ElementDTO> elements,
                               Set<Integer> visited, Set<String> semanticSeen, int depth, ParseContext ctx) {
        if (node == null || depth > 20) return;
        int id = System.identityHashCode(node);
        if (!visited.add(id)) return;

        if (node instanceof Element el) {
            String localName = elementLocalName(el);
            if ("PathObject".equals(localName) || "CT_Path".equals(localName)) {
                parseBlockFromDom(el, localName, elements, semanticSeen, ctx);
                return;
            }
            NodeList children = el.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element) {
                    walkPathsOnly(child, elements, visited, semanticSeen, depth + 1, ctx);
                }
            }
            return;
        }

        String cn = node.getClass().getSimpleName();
        if (cn.contains("Path")) {
            parseBlock(node, elements, semanticSeen, ctx);
            return;
        }

        Method[] methods = node.getClass().getMethods();
        for (Method m : methods) {
            try {
                if (m.getParameterCount() != 0) continue;
                String name = m.getName();
                if (!(name.startsWith("get") || name.startsWith("is"))) continue;
                if ("getClass".equals(name)) continue;

                Object val = m.invoke(node);
                if (val == null) continue;

                if (val instanceof List<?>) {
                    for (Object item : (List<?>) val) {
                        walkPathsOnly(item, elements, visited, semanticSeen, depth + 1, ctx);
                    }
                } else if (val instanceof Map<?, ?>) {
                    for (Object entryVal : ((Map<?, ?>) val).values()) {
                        walkPathsOnly(entryVal, elements, visited, semanticSeen, depth + 1, ctx);
                    }
                } else if (!isJdkType(val.getClass())) {
                    walkPathsOnly(val, elements, visited, semanticSeen, depth + 1, ctx);
                }
            } catch (Exception ignore) {
            }
        }
    }

    /** 模板/DOM 中 ofdrw 未覆盖的 TextObject（如仅存在于 Template 的标签） */
    private void collectSupplementTextFromRoots(List<Object> roots, List<ElementDTO> elements,
                                                ParseContext ctx, Set<String> semanticSeen) {
        Set<Integer> visited = new HashSet<>();
        for (Object root : roots) {
            walkSupplementTextOnly(root, elements, visited, semanticSeen, 0, ctx);
        }
    }

    private void walkSupplementTextOnly(Object node, List<ElementDTO> elements,
                                        Set<Integer> visited, Set<String> semanticSeen,
                                        int depth, ParseContext ctx) {
        if (node == null || depth > 20) return;
        int id = System.identityHashCode(node);
        if (!visited.add(id)) return;

        if (node instanceof Element el) {
            String localName = elementLocalName(el);
            if ("TextObject".equals(localName) || "CT_Text".equals(localName)) {
                String oid = firstNonBlank(
                        el.getAttribute("ID"), el.getAttribute("Id"), el.getAttribute("id"),
                        el.getAttribute("ObjID"), el.getAttribute("ObjectID"));
                if (isNotBlank(oid) && semanticSeen.contains("TEXT|obj|" + oid.trim())) {
                    return;
                }
                parseBlockFromDom(el, localName, elements, semanticSeen, ctx);
                return;
            }
            NodeList children = el.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element) {
                    walkSupplementTextOnly(child, elements, visited, semanticSeen, depth + 1, ctx);
                }
            }
        }
    }

    private void walk(Object node, List<ElementDTO> elements,
                      Set<Integer> visited, Set<String> semanticSeen, int depth, ParseContext ctx) {
        if (node == null || depth > 20) return;
        int id = System.identityHashCode(node);
        if (!visited.add(id)) return;

        String cn = node.getClass().getSimpleName();

        // W3C DOM Element（来自模板XML）
        if (node instanceof Element) {
            Element el = (Element) node;
            String localName = el.getLocalName();
            if (localName == null) localName = el.getNodeName();
            // 去掉命名空间前缀
            if (localName.contains(":")) localName = localName.substring(localName.indexOf(":") + 1);

            log.debug("【walk-DOM】元素: {}, depth={}", localName, depth);

            if (RENDERABLE_TYPES.contains(localName)) {
                parseBlockFromDom(el, localName, elements, semanticSeen, ctx);
            } else {
                // 递归子节点
                NodeList children = el.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child instanceof Element) {
                        walk(child, elements, visited, semanticSeen, depth + 1, ctx);
                    }
                }
            }
            return;
        }

        // ofdrw对象（来自正文层/注释层API）
        if (RENDERABLE_TYPES.contains(cn)) {
            log.debug("【walk】发现可渲染元素: type={}, depth={}", cn, depth);
            parseBlock(node, elements, semanticSeen, ctx);
        }

        Method[] methods = node.getClass().getMethods();
        for (Method m : methods) {
            try {
                if (m.getParameterCount() != 0) continue;
                String name = m.getName();
                if (!(name.startsWith("get") || name.startsWith("is"))) continue;
                if ("getClass".equals(name)) continue;

                Object val = m.invoke(node);
                if (val == null) continue;

                if (val instanceof List<?>) {
                    for (Object item : (List<?>) val) {
                        walk(item, elements, visited, semanticSeen, depth + 1, ctx);
                    }
                } else if (val instanceof Map<?, ?>) {
                    for (Object entryVal : ((Map<?, ?>) val).values()) {
                        walk(entryVal, elements, visited, semanticSeen, depth + 1, ctx);
                    }
                }else if (!isJdkType(val.getClass())) {
                    walk(val, elements, visited, semanticSeen, depth + 1, ctx);
                }
            } catch (Exception ignore) {
            }
        }
    }

    // ==================== DOM元素解析（模板层）====================

    /**
     * 解析来自模板XML的W3C DOM Element
     */
    private void parseBlockFromDom(Element el, String localName,
                                   List<ElementDTO> elements, Set<String> semanticSeen,
                                   ParseContext ctx) {
        String boundary = el.getAttribute("Boundary");
        if (!isNotBlank(boundary)) return;
        List<Double> nums = extractNumbers(boundary);
        if (nums.size() < 4) return;

        double x = nums.get(0), y = nums.get(1), w = nums.get(2), h = nums.get(3);

        // 解析 CTM（但是否参与 Boundary 变换取决于元素类型）
        // OFD 规范：Boundary 已经是父坐标系下的 AABB；CTM 描述的是对象局部空间到父坐标系的变换，
        // 用于内部内容（Path 的 AbbreviatedData / Text 的 TextCode 坐标与字号 / Image 的单位方框）。
        // 因此对 ImageObject、TextObject 不应再把 CTM 套用到 Boundary，否则会把图片/文本放大并移位。
        // PathObject 的 AABB 只用于选择框，对渲染影响小，保留旧逻辑以最大限度避免回归。
        String ctmStr = el.getAttribute("CTM");
        Matrix ctm = parseCTMString(ctmStr);
        boolean applyCtmToBoundary = "PathObject".equals(localName) || "CT_Path".equals(localName);
        if (ctm != null && applyCtmToBoundary) {
            Rect transformed = transformRect(Rect.of(x, y, w, h), ctm);
            if (transformed != null) {
                x = transformed.x; y = transformed.y;
                w = transformed.w; h = transformed.h;
            }
        }

        ElementDTO dto = new ElementDTO();
        dto.setId(UUID.randomUUID().toString());
        dto.setXmlObjId(firstNonBlank(
                el.getAttribute("ID"),
                el.getAttribute("Id"),
                el.getAttribute("id"),
                el.getAttribute("ObjID"),
                el.getAttribute("ObjectID")
        ));
        dto.setX(x); dto.setY(y);
        dto.setWidth(safeSize(w, 1.0));
        dto.setHeight(safeSize(h, 1.0));
        dto.setRotation(0.0);
        dto.setScaleX(1.0);
        dto.setScaleY(1.0);
        dto.setIsDirty(false);
        if (ctm != null) dto.setCtm(ctm.toString());

        switch (localName) {
            case "TextObject", "CT_Text" -> {
                dto.setType("TEXT");
                parseTextFromDom(el, dto, ctx);
                applyTextCodeOriginOffset(dto, el);
                if (ctm != null) dto.setRotation(extractRotationFromMatrix(ctm));
                if (dto.getWidth() <= 0) dto.setWidth(80.0);
                if (dto.getHeight() <= 0) dto.setHeight(18.0);
            }
            case "ImageObject", "CT_Image" -> {
                dto.setType("IMAGE");
                parseImageFromDom(el, dto, ctx);
                if (dto.getWidth() <= 0) dto.setWidth(100.0);
                if (dto.getHeight() <= 0) dto.setHeight(100.0);
            }
            case "PathObject", "CT_Path" -> {
                dto.setType("PATH");
                parsePathFromDom(el, dto);
                if (dto.getWidth() <= 0) dto.setWidth(100.0);
                if (dto.getHeight() <= 0) dto.setHeight(100.0);
            }
            default -> { return; }
        }

        String fp = buildElementFingerprint(dto);
        if (!semanticSeen.add(fp)) {
            log.debug("【去重过滤】type={}, x={}, y={}", dto.getType(), dto.getX(), dto.getY());
            return;
        };

        dto.setOriginalX(dto.getX());
        dto.setOriginalY(dto.getY());
        dto.setOriginalWidth(dto.getWidth());
        dto.setOriginalHeight(dto.getHeight());
        dto.setOriginalRotation(0.0);
        elements.add(dto);
    }

    private void parseTextFromDom(Element el, ElementDTO dto, ParseContext ctx) {
        // 字号：Size 是局部空间字号，OFD 常见做法是 Size=1 + CTM 缩放编码实际字号，因此乘以 CTM 的 Y 向缩放
        String sizeAttr = el.getAttribute("Size");
        double fontSize = 12.0;
        if (isNotBlank(sizeAttr)) {
            Double s = tryParseDouble(sizeAttr);
            if (s != null && s > 0) fontSize = s;
        }
        Matrix ctmForSize = parseCTMString(firstNonBlank(el.getAttribute("CTM"), el.getAttribute("ctm")));
        if (ctmForSize != null) {
            double scaleY = Math.sqrt(ctmForSize.b * ctmForSize.b + ctmForSize.d * ctmForSize.d);
            if (Double.isFinite(scaleY) && scaleY > 0.0001) fontSize *= scaleY;
        }

        TextCodeParseResult parsed = parseTextCodesFromDomElement(el);
        dto.setContent(parsed.content);
        dto.setVerticalLayout(parsed.verticalLayout ? Boolean.TRUE : null);
        dto.setFontSize(fontSize);

        Element firstTc = getFirstTextCodeElement(el);
        String plainContent = parsed.content != null ? parsed.content.replace("\n", "").trim() : "";
        Double adv = resolveGlyphAdvanceMm(firstTc != null ? firstTc.getAttribute("DeltaX") : null);
        if (adv != null && adv > 0 && isCurrencyLikeContent(plainContent)) {
            dto.setGlyphAdvanceMm(adv);
        }

        String color = extractTextFillColorHexFromElement(el);
        if (!isNotBlank(color)) color = "#000000";
        dto.setColor(color);
        String oidDom = dto.getXmlObjId();
        if (ctx != null && isNotBlank(oidDom)) {
            String keyed = ctx.textFillHexByObjectIdPage.get(oidDom.trim());
            if (isNotBlank(keyed)) dto.setColor(keyed);
        }
        log.debug("【TEXT颜色】color={}, element={}", dto.getColor(), el.getAttribute("Boundary"));
        // 字体
        String fontFamily = "宋体";
        NodeList fontNodes = el.getElementsByTagNameNS("*", "Font");
        if (fontNodes.getLength() == 0) fontNodes = el.getElementsByTagName("Font");
        if (fontNodes.getLength() > 0 && fontNodes.item(0) instanceof Element fontEl) {
            String fn = firstNonBlank(
                    fontEl.getAttribute("FontName"),
                    fontEl.getAttribute("FamilyName")
            );
            if (isNotBlank(fn)) fontFamily = fn;
        }
        dto.setFontFamily(fontFamily);
        dto.setBold(false);
        dto.setItalic(false);
        String rotate = firstNonBlank(el.getAttribute("Rotate"), el.getAttribute("rotate"));
        Double rotation = tryParseDouble(rotate);
        if (rotation != null) dto.setRotation(rotation);
        String ctmStr = firstNonBlank(el.getAttribute("CTM"), el.getAttribute("ctm"));
        Matrix ctm = parseCTMString(ctmStr);
        if (ctm != null) {
            dto.setRotation(extractRotationFromMatrix(ctm));
        }
    }

    private void parseImageFromDom(Element el, ElementDTO dto, ParseContext ctx) {
        String resourceId = firstNonBlank(
                el.getAttribute("ResourceID"),
                el.getAttribute("ResourceId"),
                el.getAttribute("ResID")
        );
        dto.setResourceId(resourceId);

        if (isNotBlank(resourceId)) {
            String rid = resourceId.trim();
            // 跳过注释层已处理的图片
            if (ctx.annotResourceIds.contains(rid)) {
                dto.setSkip(true);
                return;
            }
            String dataUrl = ctx.resourceDataUrlById.get(rid);
            if (isNotBlank(dataUrl)) {
                dto.setImageBase64(dataUrl);
                dto.setImageData(dataUrl);
            }
        }
    }

    private void parsePathFromDom(Element el, ElementDTO dto) {
        applyOfdPathStrokeFillFromAttributes(el, dto);

        NodeList abbrevNodes = el.getElementsByTagNameNS("*", "AbbreviatedData");
        if (abbrevNodes.getLength() == 0)
            abbrevNodes = el.getElementsByTagName("AbbreviatedData");

        if (abbrevNodes.getLength() > 0) {
            String rawPath = abbrevNodes.item(0).getTextContent();
            if (isNotBlank(rawPath)) {
                // 页坐标 = CTM * 局部 + Boundary 左上（不能用已变换后的 AABB 作仅平移）
                String bnd = el.getAttribute("Boundary");
                List<Double> raw = extractNumbers(bnd);
                double rbx = raw.size() >= 1 ? raw.get(0) : 0.0;
                double rby = raw.size() >= 2 ? raw.get(1) : 0.0;
                Matrix pathCtm = parseCTMString(firstNonBlank(
                        el.getAttribute("CTM"), el.getAttribute("ctm")));
                String svgPath = convertOfdPathToSvg(rawPath.trim(), rbx, rby, pathCtm);
                dto.setPathData(svgPath);
                log.debug("【PATH-DOM】svg={}, rawBoundary=({},{}), ctm={}",
                        svgPath, rbx, rby, pathCtm);
            }
        }

        // 填充色
        if (!Boolean.FALSE.equals(dto.getPathFillEnabled())) {
            NodeList fillNodes = el.getElementsByTagNameNS("*", "FillColor");
            if (fillNodes.getLength() == 0) fillNodes = el.getElementsByTagName("FillColor");
            if (fillNodes.getLength() > 0 && fillNodes.item(0) instanceof Element fillEl) {
                dto.setFillColor(parseRgbString(fillEl.getAttribute("Value"), null));
            }
        }

        // 描边色
        if (!Boolean.FALSE.equals(dto.getPathStrokeEnabled())) {
            NodeList strokeNodes = el.getElementsByTagNameNS("*", "StrokeColor");
            if (strokeNodes.getLength() == 0) strokeNodes = el.getElementsByTagName("StrokeColor");
            if (strokeNodes.getLength() > 0 && strokeNodes.item(0) instanceof Element strokeEl) {
                dto.setStrokeColor(parseRgbString(strokeEl.getAttribute("Value"), null));
            }
        }

        // 线宽
        if (!Boolean.FALSE.equals(dto.getPathStrokeEnabled())) {
            String lineWidthAttr = el.getAttribute("LineWidth");
            if (isNotBlank(lineWidthAttr)) {
                Double lw = tryParseDouble(lineWidthAttr);
                if (lw != null && lw > 0) dto.setLineWidth(lw);
            }
        }

        if (Boolean.FALSE.equals(dto.getPathFillEnabled())) {
            dto.setFillColor(null);
        }
        if (Boolean.TRUE.equals(dto.getPathFillEnabled()) && !isNotBlank(dto.getFillColor())) {
            dto.setFillColor("#000000");
        }
        if (Boolean.TRUE.equals(dto.getPathStrokeEnabled()) && (dto.getLineWidth() == null || dto.getLineWidth() <= 0)) {
            dto.setLineWidth(0.3);
        }
        if (Boolean.TRUE.equals(dto.getPathStrokeEnabled()) && !isNotBlank(dto.getStrokeColor())) {
            dto.setStrokeColor("#222222");
        }
        if (isNotBlank(dto.getPathData()) && !isNotBlank(dto.getFillColor()) && !isNotBlank(dto.getStrokeColor())
                && !Boolean.FALSE.equals(dto.getPathStrokeEnabled())) {
            dto.setStrokeColor("#222222");
            if (dto.getLineWidth() == null || dto.getLineWidth() <= 0) {
                dto.setLineWidth(0.3);
            }
        }
        // 描边类矢量字（无 TextObject 时以 Path 画字）：有描边色但未给线宽
        if (isNotBlank(dto.getPathData()) && isNotBlank(dto.getStrokeColor()) && !Boolean.FALSE.equals(dto.getPathStrokeEnabled())
                && (dto.getLineWidth() == null || dto.getLineWidth() <= 0)) {
            dto.setLineWidth(0.25);
        }
        if (!isNotBlank(dto.getPathData())) {
            dto.setContent("[图形]");
        }
    }

    /**
     * OFD PathObject/Path 的 {@code Fill}{@code Stroke} 布尔与矢量渲染语义
     * （纯填充的整页色块为 Stroke=false，前端不得再画默认描边）
     */
    private void applyOfdPathStrokeFillFromAttributes(Element el, ElementDTO dto) {
        String f = firstNonBlank(el.getAttribute("Fill"), el.getAttribute("fill"));
        if (isNotBlank(f)) {
            if ("false".equalsIgnoreCase(f.trim())) {
                dto.setPathFillEnabled(false);
            } else if ("true".equalsIgnoreCase(f.trim())) {
                dto.setPathFillEnabled(true);
            }
        }
        String s = firstNonBlank(el.getAttribute("Stroke"), el.getAttribute("stroke"));
        if (isNotBlank(s)) {
            if ("false".equalsIgnoreCase(s.trim())) {
                dto.setPathStrokeEnabled(false);
                dto.setLineWidth(0.0);
                dto.setStrokeColor(null);
            } else if ("true".equalsIgnoreCase(s.trim())) {
                dto.setPathStrokeEnabled(true);
            }
        }
    }
    /**
     * OFD AbbreviatedData 转 SVG path。局部坐标经 {@code ctm} 变到与 Boundary 同一父系，再加上 Boundary 左上得到页坐标。
     */
    private Point toPagePoint(Matrix ctm, double boundaryX, double boundaryY, double lx, double ly) {
        Point p;
        if (ctm == null) {
            p = new Point();
            p.x = lx + boundaryX;
            p.y = ly + boundaryY;
        } else {
            p = apply(ctm, lx, ly);
            p.x += boundaryX;
            p.y += boundaryY;
        }
        return p;
    }

    private double effectiveArcRadius(Matrix ctm, double rx, double ry) {
        if (ctm == null) return (Math.abs(rx) + Math.abs(ry)) * 0.5;
        double s = (Math.hypot(ctm.a, ctm.b) + Math.hypot(ctm.c, ctm.d)) * 0.5;
        return (Math.abs(rx) + Math.abs(ry)) * 0.5 * s;
    }

    private String convertOfdPathToSvg(String ofdPath, double boundaryX, double boundaryY, Matrix ctm) {
        if (!isNotBlank(ofdPath)) return ofdPath;
        StringBuilder svg = new StringBuilder();
        String[] tokens = ofdPath.trim().split("\\s+");
        int i = 0;
        while (i < tokens.length) {
            String cmd = tokens[i];
            switch (cmd) {
                case "S", "M" -> {
                    if (i + 2 < tokens.length) {
                        double lx = parseCoord(tokens[i + 1]);
                        double ly = parseCoord(tokens[i + 2]);
                        Point t = toPagePoint(ctm, boundaryX, boundaryY, lx, ly);
                        svg.append(String.format(Locale.ROOT, "M %.4f,%.4f ", t.x, t.y));
                        i += 3;
                    } else { i++; }
                }
                case "L" -> {
                    if (i + 2 < tokens.length) {
                        double lx = parseCoord(tokens[i + 1]);
                        double ly = parseCoord(tokens[i + 2]);
                        Point t = toPagePoint(ctm, boundaryX, boundaryY, lx, ly);
                        svg.append(String.format(Locale.ROOT, "L %.4f,%.4f ", t.x, t.y));
                        i += 3;
                    } else { i++; }
                }
                case "B" -> {
                    if (i + 6 < tokens.length) {
                        Point p1 = toPagePoint(ctm, boundaryX, boundaryY, parseCoord(tokens[i + 1]), parseCoord(tokens[i + 2]));
                        Point p2 = toPagePoint(ctm, boundaryX, boundaryY, parseCoord(tokens[i + 3]), parseCoord(tokens[i + 4]));
                        Point p3 = toPagePoint(ctm, boundaryX, boundaryY, parseCoord(tokens[i + 5]), parseCoord(tokens[i + 6]));
                        svg.append(String.format(Locale.ROOT,
                                "C %.4f,%.4f %.4f,%.4f %.4f,%.4f ", p1.x, p1.y, p2.x, p2.y, p3.x, p3.y));
                        i += 7;
                    } else { i++; }
                }
                case "Q" -> {
                    if (i + 4 < tokens.length) {
                        Point p1 = toPagePoint(ctm, boundaryX, boundaryY, parseCoord(tokens[i + 1]), parseCoord(tokens[i + 2]));
                        Point p2 = toPagePoint(ctm, boundaryX, boundaryY, parseCoord(tokens[i + 3]), parseCoord(tokens[i + 4]));
                        svg.append(String.format(Locale.ROOT,
                                "Q %.4f,%.4f %.4f,%.4f ", p1.x, p1.y, p2.x, p2.y));
                        i += 5;
                    } else { i++; }
                }
                case "A" -> {
                    if (i + 7 < tokens.length) {
                        double rx0 = parseCoord(tokens[i + 1]);
                        double ry0 = parseCoord(tokens[i + 2]);
                        double angle = parseCoord(tokens[i + 3]);
                        String large = tokens[i + 4];
                        String sweep = tokens[i + 5];
                        Point t = toPagePoint(ctm, boundaryX, boundaryY, parseCoord(tokens[i + 6]), parseCoord(tokens[i + 7]));
                        double r = effectiveArcRadius(ctm, rx0, ry0);
                        if (r < 1e-5) r = 0.01;
                        svg.append(String.format(Locale.ROOT,
                                "A %.4f,%.4f %.4f %s %s %.4f,%.4f ",
                                r, r, angle, large, sweep, t.x, t.y));
                        i += 8;
                    } else { i++; }
                }
                case "C" -> {
                    svg.append("Z ");
                    i++;
                }
                default -> {
                    log.debug("【PATH转换】未知OFD命令: {}", cmd);
                    i++;
                }
            }
        }
        return svg.toString().trim();
    }

    private double parseCoord(String s) {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return 0.0; }
    }
    /**
     * 解析 CTM 属性字符串 "a b c d e f"
     */
    private Matrix parseCTMString(String ctmStr) {
        if (!isNotBlank(ctmStr)) return null;
        List<Double> nums = extractNumbers(ctmStr);
        if (nums.size() < 6) return null;
        Matrix m = new Matrix();
        m.a = nums.get(0); m.b = nums.get(1);
        m.c = nums.get(2); m.d = nums.get(3);
        m.e = nums.get(4); m.f = nums.get(5);
        return m;
    }

    // ==================== ofdrw对象解析（正文层）====================

    private void parseBlock(Object block, List<ElementDTO> elements,
                            Set<String> semanticSeen, ParseContext ctx) {
        if (block == null) return;
        String className = block.getClass().getSimpleName();

        Rect boundary = getBoundaryRect(block);
        Matrix ctm = getCTM(block);

        // OFD 规范：Boundary 已是父坐标系下的 AABB；CTM 描述局部空间到父坐标系的变换，
        // 不该再套用到 Boundary 上。仅 PathObject 因 AABB 不直接参与渲染而保留旧行为，
        // 避免极端 CTM（缩放/旋转）令选择框完全错位。
        boolean applyCtmToBoundary = className.contains("Path");
        Rect finalRect;
        if (boundary != null) {
            finalRect = (ctm != null && applyCtmToBoundary) ? transformRect(boundary, ctm) : boundary;
        } else if (ctm != null) {
            finalRect = getFallbackRectFromCTMAndObject(ctm, block);
        } else {
            finalRect = getFallbackRectFromObject(block);
        }

        if (finalRect == null || finalRect.x == null || finalRect.y == null) return;

        ElementDTO dto = new ElementDTO();
        dto.setId(UUID.randomUUID().toString());
        dto.setXmlObjId(firstNonBlank(
                invokeStringAny(block, "getID"),
                invokeStringAny(block, "getId"),
                invokeStringAny(block, "getObjID"),
                invokeStringAny(block, "getObjectID")
        ));
        dto.setRotation(0.0);
        dto.setScaleX(1.0);
        dto.setScaleY(1.0);
        dto.setIsDirty(false);
        if (ctm != null) dto.setCtm(ctm.toString());
        if (ctm != null) dto.setRotation(extractRotationFromMatrix(ctm));

        dto.setX(finalRect.x);
        dto.setY(finalRect.y);
        dto.setWidth(safeSize(finalRect.w, 1.0));
        dto.setHeight(safeSize(finalRect.h, 1.0));

        if (className.contains("Text")) {
            dto.setType("TEXT");
            parseTextContent(block, dto, ctm, finalRect, ctx);
            applyTextDomOverrides(dto, ctx);
            if (dto.getWidth() == null || dto.getWidth() <= 0) dto.setWidth(80.0);
            if (dto.getHeight() == null || dto.getHeight() <= 0) dto.setHeight(18.0);

        } else if (className.contains("Image")) {
            dto.setType("IMAGE");
            parseImageContent(block, dto, ctx);
            if (dto.getSkip() != null && dto.getSkip()) return;
            if (dto.getWidth() == null || dto.getWidth() <= 0) dto.setWidth(100.0);
            if (dto.getHeight() == null || dto.getHeight() <= 0) dto.setHeight(100.0);
            log.debug("ImageRect boundary={} ctm={} final={}", boundary, ctm, finalRect);

        } else if (className.contains("Path")) {
            dto.setType("PATH");
            double rbx = (boundary != null && boundary.x != null) ? boundary.x : 0.0;
            double rby = (boundary != null && boundary.y != null) ? boundary.y : 0.0;
            parsePathContent(block, dto, ctm, rbx, rby);
            applyPathDomOverrides(dto, ctx);
            if (dto.getWidth() == null || dto.getWidth() <= 0) dto.setWidth(100.0);
            if (dto.getHeight() == null || dto.getHeight() <= 0) dto.setHeight(100.0);

        } else {
            return;
        }

        String fp = buildElementFingerprint(dto);
        if (!semanticSeen.add(fp)) return;

        dto.setOriginalX(dto.getX());
        dto.setOriginalY(dto.getY());
        dto.setOriginalWidth(dto.getWidth());
        dto.setOriginalHeight(dto.getHeight());
        dto.setOriginalRotation(0.0);
        elements.add(dto);
    }

    private Rect getBoundaryRect(Object block) {
        try {
            Object boundary = invokeAny(block, "getBoundary");
            if (boundary == null) return null;

            Double x = invokeDoubleAny(boundary, "getX", "getLeft", "getTopLeftX");
            Double y = invokeDoubleAny(boundary, "getY", "getTop", "getTopLeftY");
            Double w = invokeDoubleAny(boundary, "getWidth", "getW");
            Double h = invokeDoubleAny(boundary, "getHeight", "getH");

            if (x == null || y == null) {
                List<Double> nums = extractNumbers(String.valueOf(boundary));
                if (nums.size() >= 4) {
                    if (x == null) x = nums.get(0);
                    if (y == null) y = nums.get(1);
                    if (w == null) w = nums.get(2);
                    if (h == null) h = nums.get(3);
                }
            }
            if (x == null || y == null) return null;
            return Rect.of(x, y, w == null ? 0.0 : w, h == null ? 0.0 : h);
        } catch (Exception e) {
            return null;
        }
    }

    private Matrix getCTM(Object block) {
        try {
            Object ctm = invokeAny(block, "getCTM", "getCtm", "getTransform");
            if (ctm == null) return null;

            Double a = invokeDoubleAny(ctm, "getA", "getM11", "getScaleX");
            Double b = invokeDoubleAny(ctm, "getB", "getM12", "getShearY");
            Double c = invokeDoubleAny(ctm, "getC", "getM21", "getShearX");
            Double d = invokeDoubleAny(ctm, "getD", "getM22", "getScaleY");
            Double e = invokeDoubleAny(ctm, "getE", "getTx", "getTranslateX");
            Double f = invokeDoubleAny(ctm, "getF", "getTy", "getTranslateY");

            Matrix m = new Matrix();
            m.a = a == null ? 1.0 : a;
            m.b = b == null ? 0.0 : b;
            m.c = c == null ? 0.0 : c;
            m.d = d == null ? 1.0 : d;
            m.e = e == null ? 0.0 : e;
            m.f = f == null ? 0.0 : f;
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    private Rect getFallbackRectFromCTMAndObject(Matrix ctm, Object block) {
        Rect r = new Rect();
        r.x = ctm.e; r.y = ctm.f;
        r.w = defaultIfNull(invokeDoubleAny(block, "getWidth", "getW"), 50.0);
        r.h = defaultIfNull(invokeDoubleAny(block, "getHeight", "getH"), 20.0);
        return r;
    }

    private Rect getFallbackRectFromObject(Object block) {
        Rect r = new Rect();
        r.x = defaultIfNull(invokeDoubleAny(block, "getX", "getLeft"), 0.0);
        r.y = defaultIfNull(invokeDoubleAny(block, "getY", "getTop"), 0.0);
        r.w = defaultIfNull(invokeDoubleAny(block, "getWidth", "getW"), 50.0);
        r.h = defaultIfNull(invokeDoubleAny(block, "getHeight", "getH"), 20.0);
        return r;
    }

    private void parseTextContent(Object textObj, ElementDTO dto, Matrix ctm, Rect finalRect, ParseContext ctx) {
        try {
            Double rawSize = invokeDoubleAny(textObj, "getSize", "getFontSize");
            if (rawSize == null || rawSize <= 0) rawSize = 12.0;

            double scaleY = 1.0;
            if (ctm != null) {
                scaleY = Math.sqrt(ctm.b * ctm.b + ctm.d * ctm.d);
                if (!Double.isFinite(scaleY) || scaleY <= 0) scaleY = 1.0;
            }
            double corrected = rawSize * scaleY;

            TextCodeParseResult parsed = parseTextCodesFromOfdrw(textObj);
            String content = parsed.content;
            boolean verticalLayout = parsed.verticalLayout;
            if (content.isEmpty()) {
                Object fallback = invokeAny(textObj, "getContent", "getText");
                if (fallback != null) content = String.valueOf(fallback);
            }

            // 字号兜底：短标签才用 Boundary 高度抬字号；密码区等长串若按外接框抬大会严重偏大
            if (!verticalLayout
                    && !content.contains("\n")
                    && content.length() <= 20
                    && finalRect != null && finalRect.h != null && finalRect.h > 0.1
                    && finalRect.w != null && finalRect.w > 0
                    && finalRect.h < finalRect.w * 1.5) {
                double hBased = finalRect.h * 0.78;
                if (corrected < hBased * 0.6) corrected = hBased;
            }
            dto.setFontSize(corrected);
            dto.setContent(content);
            dto.setVerticalLayout(verticalLayout ? Boolean.TRUE : null);
            dto.setColor(parseColorFromObject(textObj, "#000000"));
            String oid = dto.getXmlObjId();
            if (ctx != null && isNotBlank(oid)) {
                String fromPageXml = ctx.textFillHexByObjectIdPage.get(oid.trim());
                if (isNotBlank(fromPageXml)) dto.setColor(fromPageXml);
            }
            parseFontInfo(textObj, dto);

        } catch (Exception e) {
            dto.setContent("");
            dto.setFontSize(12.0);
            dto.setColor("#000000");
            dto.setFontFamily("宋体");
            dto.setBold(false);
            dto.setItalic(false);
        }
    }

    private String parseColorFromObject(Object obj, String defaultColor) {
        try {
            Object fillColor = invokeAny(obj, "getFillColor", "getDefaultFillColor", "getColor");
            if (fillColor != null) {
                Object rObj = invokeAny(fillColor, "getRed", "getR", "getValueR");
                Object gObj = invokeAny(fillColor, "getGreen", "getG", "getValueG");
                Object bObj = invokeAny(fillColor, "getBlue", "getB", "getValueB");
                if (rObj != null && gObj != null && bObj != null) {
                    double rf = ((Number) rObj).doubleValue();
                    double gf = ((Number) gObj).doubleValue();
                    double bf = ((Number) bObj).doubleValue();
                    String hex = rgbTripletDoublesToHex(rf, gf, bf, null);
                    if (isNotBlank(hex)) return hex;
                }
                String[] parts = String.valueOf(fillColor).trim().split("\\s+");
                if (parts.length >= 3) {
                    Double r = tryParseDouble(parts[0]);
                    Double g = tryParseDouble(parts[1]);
                    Double b = tryParseDouble(parts[2]);
                    if (r != null && g != null && b != null) {
                        String hex = rgbTripletDoublesToHex(r, g, b, null);
                        if (isNotBlank(hex)) return hex;
                    }
                }
            }
        } catch (Exception ignore) {}
        return defaultColor;
    }

    private void parseFontInfo(Object textObj, ElementDTO dto) {
        try {
            Object font = invokeAny(textObj, "getFont", "getDefaultFont", "getFontName");
            if (font != null) {
                String fontName = invokeStringAny(font, "getFontName", "getName", "getFamily");
                if (isNotBlank(fontName)) dto.setFontFamily(fontName);
                Object bold = invokeAny(font, "getBold", "isBold");
                if (bold instanceof Boolean) dto.setBold((Boolean) bold);
                Object italic = invokeAny(font, "getItalic", "isItalic");
                if (italic instanceof Boolean) dto.setItalic((Boolean) italic);
            }
        } catch (Exception ignore) {}
        if (!isNotBlank(dto.getFontFamily())) dto.setFontFamily("宋体");
        if (dto.getBold() == null) dto.setBold(false);
        if (dto.getItalic() == null) dto.setItalic(false);
    }

    private void parseImageContent(Object imageObj, ElementDTO dto, ParseContext ctx) {
        try {
            String resourceId = firstNonBlank(
                    invokeStringAny(imageObj, "getResourceID"),
                    invokeStringAny(imageObj, "getResourceId"),
                    invokeStringAny(imageObj, "getResID"),
                    invokeStringAny(imageObj, "getResourceRef"),
                    invokeStringAny(imageObj, "getObjID")
            );
            if (isNotBlank(resourceId) && ctx.annotResourceIds.contains(resourceId.trim())) {
                dto.setSkip(true);
                return;
            }
            dto.setResourceId(resourceId);

            String imageBase64 = null;
            Object raw = invokeAny(imageObj, "getImageData", "getData", "getBinaryData", "getBytes", "getBuf");
            if (raw instanceof byte[] bytes && bytes.length > 0) {
                imageBase64 = toDataUrl(bytes);
            } else if (raw != null) {
                String str = String.valueOf(raw).trim();
                if (str.startsWith("data:image")) imageBase64 = str;
                else if (isProbablyBase64(str)) imageBase64 = toDataUrl(str);
            }

            if (!isNotBlank(imageBase64) && isNotBlank(resourceId)) {
                imageBase64 = ctx.resourceDataUrlById.get(resourceId.trim());
                log.debug("ImageObject resourceId={}, 命中={}", resourceId.trim(),
                        ctx.resourceDataUrlById.containsKey(resourceId.trim()));
            }

            if (isNotBlank(imageBase64)) {
                dto.setImageBase64(imageBase64);
                dto.setImageData(imageBase64);
            }
            dto.setContent((!isNotBlank(dto.getImageBase64())) ? "[图片]" : null);
        } catch (Exception e) {
            dto.setContent("[图片]");
        }
    }

    private void parsePathContent(Object pathObj, ElementDTO dto, Matrix ctm, double rawBoundX, double rawBoundY) {
        try {
            applyPathStrokeFillFromOfdrwObject(pathObj, dto);
            String rawPath = firstNonBlank(
                    invokeStringAny(pathObj, "getAbbreviatedData"),
                    invokeStringAny(pathObj, "getPathData"),
                    invokeStringAny(pathObj, "getData"),
                    invokeStringAny(pathObj, "toPathString")
            );
            if (isNotBlank(rawPath)) {
                dto.setPathData(convertOfdPathToSvg(rawPath.trim(), rawBoundX, rawBoundY, ctm));
            } else {
                dto.setContent("[图形]");
            }

            if (!Boolean.FALSE.equals(dto.getPathFillEnabled())) {
                dto.setFillColor(parseColorFromObject(pathObj, null));
            } else {
                dto.setFillColor(null);
            }
            try {
                if (!Boolean.FALSE.equals(dto.getPathStrokeEnabled())) {
                    Object sc = invokeAny(pathObj, "getStrokeColor", "getStroke");
                    if (sc != null) dto.setStrokeColor(parseColorFromObject(sc, null));
                }
            } catch (Exception ignore) {}

            if (!Boolean.FALSE.equals(dto.getPathStrokeEnabled())) {
                Double lineWidth = invokeDoubleAny(pathObj, "getLineWidth", "getStrokeWidth");
                if (lineWidth != null && lineWidth > 0) dto.setLineWidth(lineWidth);
            }
            if (isNotBlank(dto.getPathData()) && !isNotBlank(dto.getFillColor()) && !isNotBlank(dto.getStrokeColor())
                    && !Boolean.FALSE.equals(dto.getPathStrokeEnabled())) {
                dto.setStrokeColor("#222222");
                if (dto.getLineWidth() == null || dto.getLineWidth() <= 0) {
                    dto.setLineWidth(0.3);
                }
            }
        } catch (Exception e) {
            dto.setContent("[图形]");
        }
    }

    private void applyPathStrokeFillFromOfdrwObject(Object o, ElementDTO dto) {
        if (o == null) return;
        try {
            Object st = invokeAny(o, "isStroke", "isStroked");
            if (st instanceof Boolean && !((Boolean) st)) {
                dto.setPathStrokeEnabled(false);
                dto.setLineWidth(0.0);
                dto.setStrokeColor(null);
            } else if (st instanceof String && "false".equalsIgnoreCase(((String) st).trim())) {
                dto.setPathStrokeEnabled(false);
                dto.setLineWidth(0.0);
                dto.setStrokeColor(null);
            }
        } catch (Exception ignore) {}
        try {
            Object f = invokeAny(o, "isFill", "isFilled", "getFill");
            if (f instanceof Boolean && !((Boolean) f)) {
                dto.setPathFillEnabled(false);
            } else if (f instanceof String && "false".equalsIgnoreCase(((String) f).trim())) {
                dto.setPathFillEnabled(false);
            }
        } catch (Exception ignore) {}
    }

    // ==================== 资源索引 ====================

    private ParseContext buildParseContext(Path ofdPath, OFDReader reader, ZipFile zipFile) {
        ParseContext ctx = new ParseContext();
        ctx.zipFile = zipFile;
        try {
            indexImageResourcesFromOfdZip(ofdPath, ctx);
            log.info("ZIP资源索引完成，图片资源 {} 项", ctx.resourceDataUrlById.size());
        } catch (Exception e) {
            log.warn("ZIP资源索引失败: {}", e.getMessage());
        }
        try {
            Set<Integer> visited = new HashSet<>();
            indexResources(reader, ctx, visited, 0);
            log.info("反射兜底索引完成，合并后图片资源 {} 项", ctx.resourceDataUrlById.size());
        } catch (Exception e) {
            log.warn("反射兜底索引失败: {}", e.getMessage());
        }
        return ctx;
    }

    /**
     * 解析 OFD 数字签章（Signs/Signatures.xml → Signature.xml → SignedValue.dat），
     * 从 SES 结构提取印章图片，并按 StampAnnot 的 PageRef / Boundary 映射到各页。
     */
    private void loadDigitalSeals(OFDReader reader, ParseContext ctx) {
        ctx.digitalSealsByPage = new HashMap<>();
        if (reader == null) {
            log.debug("签章解析跳过: reader 为空");
            return;
        }
        if (!reader.hasSignature()) {
            log.debug("签章解析跳过: 文档无 Signs");
            return;
        }
        Map<Long, Integer> pageRefToIndex = buildPageRefToIndex(reader);
        List<StampAnnotEntity> entities;
        try {
            entities = reader.getStampAnnots();
        } catch (Exception e) {
            log.warn("读取签章列表失败: {}", e.getMessage());
            return;
        }
        if (entities == null || entities.isEmpty()) {
            log.info("签章解析: getStampAnnots 返回空列表");
            return;
        }

        int sealCount = 0;
        for (StampAnnotEntity entity : entities) {
            if (entity == null) continue;
            byte[] imgBytes = entity.getImageByte();
            if (imgBytes == null || imgBytes.length == 0) continue;

            String imgType = entity.getImgType();
            String dataUrl = sealBytesToDataUrl(imgBytes, imgType);
            if (!isNotBlank(dataUrl)) {
                log.warn("签章外观转图片失败: imgType={}, bytes={}", imgType, imgBytes.length);
                continue;
            }

            List<StampAnnot> stampAnnots = entity.getStampAnnots();
            if (stampAnnots == null || stampAnnots.isEmpty()) continue;

            for (StampAnnot stampAnnot : stampAnnots) {
                if (stampAnnot == null) continue;
                Integer pageIndex = resolveStampPageIndex(stampAnnot.getPageRef(), pageRefToIndex);
                ST_Box box = stampAnnot.getBoundary();
                if (pageIndex == null || box == null) {
                    log.warn("签章 PageRef 无法映射: pageRef={}, boundary={}",
                            stampAnnot.getPageRef(), stampAnnot.getBoundary());
                    continue;
                }

                Double x = box.getTopLeftX();
                Double y = box.getTopLeftY();
                Double w = box.getWidth();
                Double h = box.getHeight();
                if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) continue;

                ElementDTO dto = new ElementDTO();
                dto.setId(UUID.randomUUID().toString());
                dto.setType("SEAL");
                dto.setX(x);
                dto.setY(y);
                dto.setWidth(w);
                dto.setHeight(h);
                dto.setRotation(0.0);
                dto.setScaleX(1.0);
                dto.setScaleY(1.0);
                dto.setImageBase64(dataUrl);
                dto.setImageData(dataUrl);
                dto.setIsDirty(false);
                dto.setSkip(true);
                dto.setOriginalX(x);
                dto.setOriginalY(y);
                dto.setOriginalWidth(w);
                dto.setOriginalHeight(h);
                dto.setOriginalRotation(0.0);

                ctx.digitalSealsByPage
                        .computeIfAbsent(pageIndex, k -> new ArrayList<>())
                        .add(dto);
                sealCount++;
            }
        }
        log.info("电子签章解析完成: {} 个 StampAnnot 外观", sealCount);
    }

    /**
     * 发票等文档的签章外观常为嵌入式 mini-OFD（imgType=OFD），需 rasterize 后再展示。
     */
    /** 签章 PNG 去白底阈值（与 ofdrw AWTMaker 默认一致） */
    private static final int SEAL_BG_GRAY_THRESHOLD = 244;

    private String sealBytesToDataUrl(byte[] imgBytes, String imgType) {
        if (imgBytes == null || imgBytes.length == 0) return null;
        if (isNotBlank(imgType) && "OFD".equalsIgnoreCase(imgType.trim())) {
            return renderEmbeddedSealOfdToDataUrl(imgBytes);
        }
        if (looksLikeZip(imgBytes)) {
            String rendered = renderEmbeddedSealOfdToDataUrl(imgBytes);
            if (isNotBlank(rendered)) return rendered;
        }
        try {
            BufferedImage raw = ImageIO.read(new ByteArrayInputStream(imgBytes));
            if (raw != null) {
                return bufferedImageToTransparentSealDataUrl(raw);
            }
        } catch (Exception e) {
            log.debug("签章位图读取失败，回退原始字节: {}", e.getMessage());
        }
        String mime = sealImageMime(imgType);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imgBytes);
    }

    private boolean looksLikeZip(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    private String renderEmbeddedSealOfdToDataUrl(byte[] sealOfdBytes) {
        try (OFDReader sealReader = new OFDReader(new ByteArrayInputStream(sealOfdBytes))) {
            if (sealReader.getNumberOfPages() <= 0) return null;
            ImageMaker maker = newImageMakerForSeal(sealReader);
            BufferedImage img = maker.makePage(0);
            return bufferedImageToTransparentSealDataUrl(img);
        } catch (Exception e) {
            log.warn("渲染 OFD 签章外观失败: {}", e.getMessage());
            return null;
        }
    }

    /** ofdrw 包内通过 isStamp=true 使用透明画布；此处用反射设置同字段 */
    private ImageMaker newImageMakerForSeal(OFDReader sealReader) {
        ImageMaker maker = new ImageMaker(sealReader, 12.0);
        try {
            java.lang.reflect.Field f = org.ofdrw.converter.AWTMaker.class.getDeclaredField("isStamp");
            f.setAccessible(true);
            f.setBoolean(maker, true);
        } catch (Exception e) {
            log.debug("无法设置 ImageMaker.isStamp，将仅依赖去白底: {}", e.getMessage());
        }
        return maker;
    }

    private String bufferedImageToTransparentSealDataUrl(BufferedImage img) throws java.io.IOException {
        if (img == null) return null;
        BufferedImage transparent = ImageUtils.clearWhiteBackground(img, SEAL_BG_GRAY_THRESHOLD);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(transparent, "png", baos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private Map<Long, Integer> buildPageRefToIndex(OFDReader reader) {
        Map<Long, Integer> map = new HashMap<>();
        try {
            int pageCount = reader.getNumberOfPages();
            for (int pageNum = 1; pageNum <= pageCount; pageNum++) {
                ST_ID pageId = reader.getPageObjectId(pageNum);
                if (pageId == null || pageId.getId() == null) continue;
                map.put(pageId.getId(), pageNum - 1);
            }
        } catch (Exception e) {
            log.warn("构建 PageRef 映射失败: {}", e.getMessage());
        }
        return map;
    }

    private Integer resolveStampPageIndex(ST_RefID pageRef, Map<Long, Integer> pageRefToIndex) {
        if (pageRef == null || pageRefToIndex == null || pageRefToIndex.isEmpty()) return null;
        if (pageRef.getRefId() != null) {
            Integer idx = pageRefToIndex.get(pageRef.getRefId().getId());
            if (idx != null) return idx;
        }
        try {
            return pageRefToIndex.get(Long.parseLong(pageRef.toString().trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String sealImageMime(String imgType) {
        if (!isNotBlank(imgType)) return "image/png";
        return switch (imgType.trim().toUpperCase(Locale.ROOT)) {
            case "PNG" -> "image/png";
            case "GIF" -> "image/gif";
            case "JPG", "JPEG" -> "image/jpeg";
            case "BMP" -> "image/bmp";
            case "SVG" -> "image/svg+xml";
            default -> "image/png";
        };
    }

    private void appendDigitalSealsForPage(int pageIndex, List<ElementDTO> elements, ParseContext ctx) {
        if (ctx.digitalSealsByPage == null) return;
        List<ElementDTO> seals = ctx.digitalSealsByPage.get(pageIndex);
        if (seals == null || seals.isEmpty()) return;
        elements.addAll(seals);
    }

    private void indexImageResourcesFromOfdZip(Path ofdPath, ParseContext ctx) throws Exception {
        try (ZipFile zip = new ZipFile(ofdPath.toFile())) {
            String ofdXmlPath = findEntryIgnoreCase(zip, "OFD.xml");
            if (ofdXmlPath == null) throw new IllegalStateException("未找到 OFD.xml");

            Document ofdDoc = parseXml(zip, ofdXmlPath);
            String docRoot = getFirstTextByLocalName(ofdDoc.getDocumentElement(), "DocRoot");
            if (!isNotBlank(docRoot)) throw new IllegalStateException("OFD.xml 未找到 DocRoot");
            docRoot = normalizePath(docRoot);

            String documentXmlPath = resolvePath(docRoot, "Document.xml");
            if (zip.getEntry(documentXmlPath) == null) {
                documentXmlPath = findEntryEndsWithIgnoreCase(zip, "/" + docRoot + "/Document.xml");
                if (documentXmlPath == null)
                    documentXmlPath = findEntryEndsWithIgnoreCase(zip, "Document.xml");
            }
            if (documentXmlPath == null) throw new IllegalStateException("未找到 Document.xml");

            Document documentDoc = parseXml(zip, documentXmlPath);
            Element docRootElem = documentDoc.getDocumentElement();

            List<String> resXmlPaths = new ArrayList<>();
            for (String tag : new String[]{"PublicRes", "DocumentRes"}) {
                for (String v : getAllTextByLocalName(docRootElem, tag)) {
                    if (isNotBlank(v)) {
                        resXmlPaths.add(resolvePath(parentDir(documentXmlPath), normalizePath(v.trim())));
                    }
                }
            }

            for (String resXml0 : new HashSet<>(resXmlPaths)) {
                String resXml = resXml0;
                if (zip.getEntry(resXml) == null) {
                    String fallback = findEntryEndsWithIgnoreCase(zip, "/" + filename(resXml));
                    if (fallback == null) continue;
                    resXml = fallback;
                }
                Document resDoc = parseXml(zip, resXml);
                Element resRoot = resDoc.getDocumentElement();

                NodeList mediaNodes = resRoot.getElementsByTagNameNS("*", "MultiMedia");
                if (mediaNodes.getLength() == 0)
                    mediaNodes = resRoot.getElementsByTagName("MultiMedia");

                for (int i = 0; i < mediaNodes.getLength(); i++) {
                    Node n = mediaNodes.item(i);
                    if (!(n instanceof Element mm)) continue;

                    String id = firstNonBlank(mm.getAttribute("ID"),
                            mm.getAttribute("Id"), mm.getAttribute("id"));
                    if (!isNotBlank(id)) continue;

                    String mediaFile = getFirstTextByLocalName(mm, "MediaFile");
                    if (!isNotBlank(mediaFile)) continue;

                    mediaFile = normalizePath(mediaFile.trim());
                    String mediaPath = resolvePath(parentDir(resXml), mediaFile);

                    ZipEntry mediaEntry = zip.getEntry(mediaPath);
                    if (mediaEntry == null) {
                        String fallback = findEntryEndsWithIgnoreCase(zip, "/" + filename(mediaPath));
                        if (fallback == null) continue;
                        mediaPath = fallback;
                        mediaEntry = zip.getEntry(mediaPath);
                    }
                    if (mediaEntry == null) continue;

                    byte[] bytes = readAllBytes(zip, mediaEntry);
                    if (bytes == null || bytes.length == 0) continue;

                    String dataUrl = toBrowserSafeDataUrl(mediaPath, bytes);
                    if (isNotBlank(dataUrl)) {
                        ctx.resourceDataUrlById.putIfAbsent(id.trim(), dataUrl);
                        ctx.resourcePathById.putIfAbsent(id.trim(), mediaPath);
                    }
                }
            }

            indexResDirectory(zip, docRoot, ctx);
        }
    }

    private void indexResDirectory(ZipFile zip, String docRootDir, ParseContext ctx) {
        String[] resDirs = {docRootDir + "/Res/", docRootDir + "/res/", "Res/", "res/"};
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            boolean inResDir = false;
            for (String dir : resDirs) {
                if (name.startsWith(dir)) { inResDir = true; break; }
            }
            if (!inResDir) continue;

            String ext = getExtension(name).toLowerCase(Locale.ROOT);
            if (!IMAGE_EXTENSIONS.contains(ext)) continue;

            String fileKey = filename(name);
            if (ctx.resourceDataUrlById.containsKey(fileKey)) continue;

            try {
                byte[] bytes = readAllBytes(zip, entry);
                if (bytes == null || bytes.length == 0) continue;
                String dataUrl = toBrowserSafeDataUrl(name, bytes);
                if (isNotBlank(dataUrl)) {
                    ctx.resourceDataUrlById.putIfAbsent(fileKey, dataUrl);
                    ctx.resourcePathById.putIfAbsent(fileKey, name);
                }
            } catch (Exception ignore) {}
        }
    }

    private void indexResources(Object node, ParseContext ctx, Set<Integer> visited, int depth) {
        if (node == null || depth > 20) return;
        if (!visited.add(System.identityHashCode(node))) return;

        registerResourceCandidate(node, ctx);

        for (Method m : node.getClass().getMethods()) {
            try {
                if (m.getParameterCount() != 0) continue;
                String name = m.getName();
                if (!(name.startsWith("get") || name.startsWith("is"))) continue;
                if ("getClass".equals(name)) continue;
                Object val = m.invoke(node);
                if (val == null) continue;
                if (val instanceof List<?> list) {
                    for (Object item : list) indexResources(item, ctx, visited, depth + 1);
                } else if (val instanceof Map<?, ?> map) {
                    for (Object v : map.values()) indexResources(v, ctx, visited, depth + 1);
                } else if (!isJdkType(val.getClass())) {
                    indexResources(val, ctx, visited, depth + 1);
                }
            } catch (Exception ignore) {}
        }
    }

    private void registerResourceCandidate(Object obj, ParseContext ctx) {
        if (obj == null) return;
        String cn = obj.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        boolean isResource = RESOURCE_CLASS_KEYWORDS.stream().anyMatch(cn::contains);
        if (!isResource) return;

        String id = firstNonBlank(
                invokeStringAny(obj, "getID"), invokeStringAny(obj, "getId"),
                invokeStringAny(obj, "getResourceID"), invokeStringAny(obj, "getObjID")
        );
        if (!isNotBlank(id)) return;
        id = id.trim();

        if (!ctx.resourceDataUrlById.containsKey(id)) {
            Object raw = invokeAny(obj, "getData", "getMediaData", "getBinaryData", "getBytes", "getBuf");
            if (raw instanceof byte[] bytes && bytes.length > 0) {
                String dataUrl = toBrowserSafeDataUrl(null, bytes);
                if (isNotBlank(dataUrl)) ctx.resourceDataUrlById.putIfAbsent(id, dataUrl);
            } else if (raw != null) {
                String str = String.valueOf(raw).trim();
                if (str.startsWith("data:image")) ctx.resourceDataUrlById.putIfAbsent(id, str);
                else if (isProbablyBase64(str)) {
                    String dataUrl = toDataUrl(str);
                    if (isNotBlank(dataUrl)) ctx.resourceDataUrlById.putIfAbsent(id, dataUrl);
                }
            }
        }
        if (!ctx.resourcePathById.containsKey(id)) {
            String path = firstNonBlank(
                    invokeStringAny(obj, "getMediaFile"), invokeStringAny(obj, "getFileLoc"),
                    invokeStringAny(obj, "getPath"), invokeStringAny(obj, "getHref")
            );
            if (isNotBlank(path)) ctx.resourcePathById.putIfAbsent(id, path.trim());
        }
    }

    // ==================== XML工具 ====================

    private Document parseXmlBytes(byte[] bytes) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private Document parseXml(ZipFile zip, String entryPath) throws Exception {
        ZipEntry e = zip.getEntry(entryPath);
        if (e == null) throw new IllegalStateException("ZIP中不存在: " + entryPath);
        try (InputStream is = zip.getInputStream(e)) {
            return parseXmlBytes(is.readAllBytes());
        }
    }

    private byte[] readZipEntry(ZipFile zip, String path) {
        try {
            ZipEntry e = zip.getEntry(path);
            if (e == null) return null;
            try (InputStream is = zip.getInputStream(e)) {
                return is.readAllBytes();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private String getFirstTextByLocalName(Element root, String localName) {
        List<String> all = getAllTextByLocalName(root, localName);
        return all.isEmpty() ? null : all.get(0);
    }

    private List<String> getAllTextByLocalName(Element root, String localName) {
        List<String> out = new ArrayList<>();
        NodeList nsNodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nsNodes.getLength(); i++) {
            String t = nsNodes.item(i).getTextContent();
            if (isNotBlank(t)) out.add(t.trim());
        }
        if (out.isEmpty()) {
            NodeList nodes = root.getElementsByTagName(localName);
            for (int i = 0; i < nodes.getLength(); i++) {
                String t = nodes.item(i).getTextContent();
                if (isNotBlank(t)) out.add(t.trim());
            }
        }
        return out;
    }

    private String findEntryIgnoreCase(ZipFile zip, String target) {
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.getName().equalsIgnoreCase(target)) return e.getName();
        }
        return null;
    }

    private String findEntryEndsWithIgnoreCase(ZipFile zip, String suffix) {
        if (suffix == null) return null;
        String sfx = suffix.toLowerCase(Locale.ROOT);
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.getName().toLowerCase(Locale.ROOT).endsWith(sfx)) return e.getName();
        }
        return null;
    }

    private byte[] readAllBytes(ZipFile zip, ZipEntry e) throws Exception {
        try (InputStream is = zip.getInputStream(e)) {
            return is.readAllBytes();
        }
    }

    // ==================== 图片工具 ====================

    private String toBrowserSafeDataUrl(String path, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        String mime = detectMimeByPathOrBytes(path, bytes);
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);

        if (p.endsWith(".jb2") || p.endsWith(".jbig2")
                || "image/jbig2".equalsIgnoreCase(mime)
                || Jbig2Converter.isLikelyJbig2(bytes)) {
            byte[] png = Jbig2Converter.convertJbig2ToPng(bytes);
            if (png == null || png.length == 0) return null;
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        }
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private String detectMimeByPathOrBytes(String path, byte[] bytes) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".png"))  return "image/png";
        if (p.endsWith(".gif"))  return "image/gif";
        if (p.endsWith(".bmp"))  return "image/bmp";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".jb2") || p.endsWith(".jbig2")) return "image/jbig2";
        if (bytes == null || bytes.length < 4) return "image/png";
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) return "image/jpeg";
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P') return "image/png";
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes[0] == 'B' && bytes[1] == 'M') return "image/bmp";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I'
                && bytes[8] == 'W' && bytes[9] == 'E') return "image/webp";
        if (Jbig2Converter.isLikelyJbig2(bytes)) return "image/jbig2";
        return "image/png";
    }

    private String toDataUrl(byte[] bytes) {
        return toBrowserSafeDataUrl(null, bytes);
    }

    private String toDataUrl(String b64) {
        if (!isNotBlank(b64)) return null;
        String clean = b64.replaceAll("\\s+", "");
        try {
            return toBrowserSafeDataUrl(null, Base64.getDecoder().decode(clean));
        } catch (Exception e) {
            String mime = clean.startsWith("/9j/") ? "image/jpeg"
                    : clean.startsWith("iVBORw0KGgo") ? "image/png" : "image/png";
            return "data:" + mime + ";base64," + clean;
        }
    }

    private boolean isProbablyBase64(String s) {
        if (!isNotBlank(s)) return false;
        String t = s.replaceAll("\\s+", "");
        return t.length() >= 256 && t.length() % 4 == 0
                && t.matches("^[A-Za-z0-9+/=]+$");
    }

    // ==================== 几何工具 ====================

    private Rect transformRect(Rect r, Matrix m) {
        if (r == null || m == null || r.x == null || r.y == null) return null;
        double x = r.x, y = r.y;
        double w = r.w == null ? 0.0 : r.w;
        double h = r.h == null ? 0.0 : r.h;
        Point p1 = apply(m, x, y), p2 = apply(m, x + w, y);
        Point p3 = apply(m, x, y + h), p4 = apply(m, x + w, y + h);
        double minX = Math.min(Math.min(p1.x, p2.x), Math.min(p3.x, p4.x));
        double minY = Math.min(Math.min(p1.y, p2.y), Math.min(p3.y, p4.y));
        double maxX = Math.max(Math.max(p1.x, p2.x), Math.max(p3.x, p4.x));
        double maxY = Math.max(Math.max(p1.y, p2.y), Math.max(p3.y, p4.y));
        return Rect.of(minX, minY, Math.max(0.0, maxX - minX), Math.max(0.0, maxY - minY));
    }

    private Point apply(Matrix m, double x, double y) {
        Point p = new Point();
        p.x = m.a * x + m.c * y + m.e;
        p.y = m.b * x + m.d * y + m.f;
        return p;
    }

    // ==================== 反射工具 ====================

    private Object invokeAny(Object target, String... methodNames) {
        if (target == null) return null;
        for (String name : methodNames) {
            try {
                Object v = target.getClass().getMethod(name).invoke(target);
                if (v != null) return v;
            } catch (Exception ignore) {}
        }
        return null;
    }

    private String invokeStringAny(Object target, String... methodNames) {
        Object v = invokeAny(target, methodNames);
        return v == null ? null : String.valueOf(v);
    }

    private Double invokeDoubleAny(Object target, String... methodNames) {
        Object v = invokeAny(target, methodNames);
        return v == null ? null : tryParseDouble(String.valueOf(v));
    }

    // ==================== 通用工具 ====================

    private String parseRgbString(String val, String defaultColor) {
        if (!isNotBlank(val)) return defaultColor;
        String[] parts = val.trim().split("\\s+");
        if (parts.length >= 3) {
            Double r = tryParseDouble(parts[0]);
            Double g = tryParseDouble(parts[1]);
            Double b = tryParseDouble(parts[2]);
            if (r != null && g != null && b != null) {
                String hex = rgbTripletDoublesToHex(r, g, b, null);
                if (isNotBlank(hex)) return hex;
            }
            try {
                return String.format(Locale.ROOT, "#%02X%02X%02X",
                        clamp255(Integer.parseInt(parts[0])),
                        clamp255(Integer.parseInt(parts[1])),
                        clamp255(Integer.parseInt(parts[2])));
            } catch (Exception ignore) {}
        }
        return defaultColor;
    }

    /** OFD 常见 0–1 浮点 RGB；误当 0–255 会变成近黑。 */
    private static String rgbTripletDoublesToHex(double rf, double gf, double bf, String fallback) {
        if (!Double.isFinite(rf) || !Double.isFinite(gf) || !Double.isFinite(bf)) return fallback;
        double max = Math.max(rf, Math.max(gf, bf));
        int r, g, b;
        if (max <= 1.0001) {
            r = clamp255((int) Math.round(rf * 255.0));
            g = clamp255((int) Math.round(gf * 255.0));
            b = clamp255((int) Math.round(bf * 255.0));
        } else {
            r = clamp255((int) Math.round(rf));
            g = clamp255((int) Math.round(gf));
            b = clamp255((int) Math.round(bf));
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private List<Double> extractNumbers(String s) {
        List<Double> result = new ArrayList<>();
        if (s == null) return result;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("-?\\d+\\.?\\d*").matcher(s);
        while (matcher.find()) {
            Double v = tryParseDouble(matcher.group());
            if (v != null) result.add(v);
        }
        return result;
    }

    private void addIfNotNull(List<Object> list, Object val) {
        if (val == null) return;
        if (val instanceof List<?> l) list.addAll(l);
        else list.add(val);
    }

    private boolean isJdkType(Class<?> c) {
        Package p = c.getPackage();
        if (p == null) return false;
        String pn = p.getName();
        return pn.startsWith("java.") || pn.startsWith("javax.") || pn.startsWith("jdk.");
    }

    private Double safeSize(Double v, Double fallback) {
        return (v == null || !Double.isFinite(v) || v <= 0) ? fallback : v;
    }

    private Double tryParseDouble(String s) {
        try { return s == null ? null : Double.parseDouble(s.trim()); }
        catch (Exception e) { return null; }
    }

    private Double defaultIfNull(Double v, Double dft) { return v == null ? dft : v; }

    private String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) if (isNotBlank(v)) return v.trim();
        return null;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String normalizePath(String p) {
        return p == null ? null : p.replace("\\", "/").replaceAll("^\\./", "").trim();
    }

    private String parentDir(String p) {
        if (!isNotBlank(p)) return "";
        int idx = p.lastIndexOf('/');
        return idx < 0 ? "" : p.substring(0, idx);
    }

    private String filename(String p) {
        if (!isNotBlank(p)) return p;
        int idx = p.lastIndexOf('/');
        return idx < 0 ? p : p.substring(idx + 1);
    }

    private String getExtension(String p) {
        if (!isNotBlank(p)) return "";
        int dot = p.lastIndexOf('.');
        return dot < 0 ? "" : p.substring(dot + 1);
    }

    private String resolvePath(String baseDir, String rel) {
        String b = normalizePath(baseDir), r = normalizePath(rel);
        if (!isNotBlank(b)) return r;
        if (!isNotBlank(r)) return b;
        if (r.startsWith("/")) return r.substring(1);
        String combined = b + "/" + r;
        try {
            String n = new java.net.URI(combined).normalize().getPath();
            return n.startsWith("/") ? n.substring(1) : n;
        } catch (Exception e) { return combined; }
    }

    private String getFileNameWithoutExt(String filename) {
        if (filename == null) return "未命名文档";
        int slashIdx = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = slashIdx >= 0 ? filename.substring(slashIdx + 1) : filename;
        int dotIndex = name.lastIndexOf('.');
        String result = dotIndex > 0 ? name.substring(0, dotIndex) : name;
        return isNotBlank(result) ? result : "未命名文档";
    }

    private String buildElementFingerprint(ElementDTO d) {
        if ("TEXT".equals(d.getType())) {
            // ofdrw 优先遍历；同 ID 的模板 DOM 文本不再重复叠层（避免「密码区/销售方」偏移）
            if (isNotBlank(d.getXmlObjId())) {
                return "TEXT|obj|" + d.getXmlObjId().trim();
            }
            // 文本去重不使用CTM，避免模板DOM与ofdrw反射对象因矩阵表达差异产生重复叠层
            return String.join("|",
                    safeStr(d.getType()),
                    safeStr(d.getContent()),
                    q(d.getX()), q(d.getY()), q(d.getWidth()), q(d.getHeight())
            );
        }
        if ("IMAGE".equals(d.getType())) {
            // PDF 转 OFD 等场景：同一 ImageObject 可能重复出现
            if (isNotBlank(d.getResourceId())) {
                return "IMAGE|res|" + d.getResourceId().trim();
            }
            if (isNotBlank(d.getXmlObjId())) {
                return "IMAGE|obj|" + d.getXmlObjId().trim();
            }
            return String.join("|",
                    safeStr(d.getType()),
                    q(d.getX()), q(d.getY()), q(d.getWidth()), q(d.getHeight())
            );
        }
        if ("PATH".equals(d.getType()) && isNotBlank(d.getXmlObjId())) {
            return "PATH|obj|" + d.getXmlObjId().trim();
        }
        return String.join("|",
                safeStr(d.getType()),
                safeStr(d.getResourceId()),
                safeStr(d.getContent()),
                q(d.getX()), q(d.getY()), q(d.getWidth()), q(d.getHeight()),
                safeStr(d.getCtm()),
                safeStr(d.getPathData())  // ← 加这一行，PATH不同路径数据不会被去重
        );
    }

    private String q(Double v) {
        return v == null ? "n" : String.format(Locale.ROOT, "%.3f", v);
    }

    private String safeStr(String s) { return s == null ? "" : s.trim(); }

    private double extractRotationFromMatrix(Matrix m) {
        if (m == null) return 0.0;
        double rad = Math.atan2(m.b, m.a);
        double deg = Math.toDegrees(rad);
        if (!Double.isFinite(deg)) return 0.0;
        if (deg > 180.0) deg -= 360.0;
        if (deg < -180.0) deg += 360.0;
        return deg;
    }

    private boolean isLikelyOfdPathData(String pathData) {
        if (!isNotBlank(pathData)) return false;
        return pathData.contains("M") || pathData.contains("L") || pathData.contains("B")
                || pathData.contains("Q") || pathData.contains("A");
    }

    private String ofdPathToJsonPointPairs(String pathData, double baseX, double baseY) {
        if (!isNotBlank(pathData)) return null;
        List<double[]> points = new ArrayList<>();
        String[] tokens = pathData.trim().split("\\s+");
        int i = 0;
        while (i < tokens.length) {
            String cmd = tokens[i];
            if (("M".equals(cmd) || "S".equals(cmd) || "L".equals(cmd)) && i + 2 < tokens.length) {
                Double x = tryParseDouble(tokens[i + 1]);
                Double y = tryParseDouble(tokens[i + 2]);
                if (x != null && y != null) points.add(new double[]{x - baseX, y - baseY});
                i += 3;
            } else {
                i++;
            }
        }
        if (points.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int j = 0; j < points.size(); j++) {
            if (j > 0) sb.append(",");
            sb.append(String.format(Locale.ROOT, "[%.4f,%.4f]", points.get(j)[0], points.get(j)[1]));
        }
        sb.append("]");
        return sb.toString();
    }

    // ==================== 内部类 ====================

    private static class ParseContext {
        final Map<String, String> resourceDataUrlById = new ConcurrentHashMap<>();
        final Map<String, String> resourcePathById    = new ConcurrentHashMap<>();
        final Set<String> annotResourceIds = new HashSet<>();
        /** 各页电子签章（Signs/），只读展示，不参与保存回写 */
        Map<Integer, List<ElementDTO>> digitalSealsByPage = new HashMap<>();
        /** 当前页/模板 XML：TextObject 的 XML ID → #RRGGBB */
        final Map<String, String> textFillHexByObjectIdPage = new LinkedHashMap<>();
        /** 当前页/模板 XML：TextObject 的 XML ID → 几何与文本 */
        final Map<String, TextObjectDomInfo> textDomByObjectIdPage = new LinkedHashMap<>();
        /** 当前页/模板 XML：PathObject 的 XML ID → Boundary + 路径数据 */
        final Map<String, PathObjectDomInfo> pathDomByObjectIdPage = new LinkedHashMap<>();
        ZipFile zipFile;
    }

    private static class PathObjectDomInfo {
        Double x, y, w, h;
        String abbrevData;
        String ctmStr;
    }

    private static class TextObjectDomInfo {
        Double boundaryX, boundaryY;
        Double w, h;
        Double tcOffX, tcOffY;
        String content;
        Boolean verticalLayout;
        Boolean passwordGrid;
        Boolean preferDomGeometry;
        Double fontSize;
        Double glyphAdvanceMm;
    }

    private static class Rect {
        Double x, y, w, h;
        static Rect of(double x, double y, double w, double h) {
            Rect r = new Rect(); r.x = x; r.y = y; r.w = w; r.h = h; return r;
        }
        @Override public String toString() {
            return "x=" + x + ",y=" + y + ",w=" + w + ",h=" + h;
        }
    }

    private static class Matrix {
        double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;
        @Override public String toString() {
            return "a=" + a + ",b=" + b + ",c=" + c + ",d=" + d + ",e=" + e + ",f=" + f;
        }
    }

    private static class Point { double x, y; }
}
