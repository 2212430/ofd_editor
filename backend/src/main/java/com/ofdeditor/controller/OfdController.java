package com.ofdeditor.controller;

import com.ofdeditor.dto.AnnotationDTO;
import com.ofdeditor.dto.OfdDocumentDTO;
import com.ofdeditor.dto.PdfExportRequest;
import com.ofdeditor.dto.SplitOfdRequest;
import com.ofdeditor.service.AnnotationService;
import com.ofdeditor.service.ConversionService;
import com.ofdeditor.service.OfdCacheService;
import com.ofdeditor.service.OfdMergeService;
import com.ofdeditor.service.OfdParseService;
import com.ofdeditor.service.OfdRebuildService;
import com.ofdeditor.service.OfdSplitService;
import com.ofdeditor.service.PdfMergeService;
import com.ofdeditor.service.PdfNativeService;
import com.ofdeditor.util.SplitPayloadUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/ofd")
@RequiredArgsConstructor
public class OfdController {

    private final OfdParseService parseService;
    private final OfdMergeService mergeService;
    private final OfdSplitService splitService;
    private final PdfMergeService pdfMergeService;
    private final PdfNativeService pdfNativeService;
    private final ConversionService conversionService;
    private final OfdRebuildService rebuildService;
    private final OfdCacheService cacheService;
    private final AnnotationService annotationService;

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OFD Editor Backend Running!");
    }

    /**
     * 上传并解析OFD文件
     * 同时读取OFD内Annotation层，初始化到缓存
     */
    @PostMapping("/parse")
    public ResponseEntity<?> parseOfd(@RequestParam("file") MultipartFile file) {
        try {
            log.info("收到解析请求: {}, 大小: {} bytes",
                    file.getOriginalFilename(), file.getSize());

            if (file.isEmpty()) return ResponseEntity.badRequest().body("文件不能为空");

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".ofd"))
                return ResponseEntity.badRequest().body("请上传OFD格式文件");

            // 缓存原始字节
            String fileId = UUID.randomUUID().toString();
            byte[] fileBytes = file.getBytes();
            cacheService.put(fileId, fileBytes);

            // 解析OFD内容
            OfdDocumentDTO result = parseService.parseOfd(file);
            result.setFileId(fileId);

            // ✅ 解析OFD内已有的Annotation层，初始化到注释缓存
            try {
                Map<Integer, List<AnnotationDTO>> existingAnnotations =
                        parseService.parseAnnotations(fileBytes);
                annotationService.initFromOfd(fileId, existingAnnotations);
                log.info("初始化注释缓存完成, fileId={}, 共{}页有注释",
                        fileId, existingAnnotations.size());
            } catch (Exception e) {
                log.warn("解析OFD注释层失败（可能无注释层）: {}", e.getMessage());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("解析OFD失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("解析失败: " + e.getMessage());
        }
    }

    /**
     * 原生解析 PDF：不栅格化，仅返回每页可视尺寸，渲染交给前端 PDF.js。
     * 缓存 PDF 原始字节并初始化空注释层，复用注释 CRUD 接口。
     */
    @PostMapping("/parse-pdf")
    public ResponseEntity<?> parsePdf(@RequestParam("file") MultipartFile file) {
        try {
            log.info("收到原生PDF解析请求: {}, 大小: {} bytes",
                    file.getOriginalFilename(), file.getSize());

            if (file.isEmpty()) return ResponseEntity.badRequest().body("文件不能为空");

            String filename = file.getOriginalFilename();
            if (filename == null || !filename.toLowerCase().endsWith(".pdf"))
                return ResponseEntity.badRequest().body("请上传PDF格式文件");

            String fileId = UUID.randomUUID().toString();
            byte[] fileBytes = file.getBytes();
            cacheService.put(fileId, fileBytes);

            OfdDocumentDTO result = pdfNativeService.parse(fileBytes, getNameWithoutExt(filename));
            result.setFileId(fileId);

            // 标记为 PDF（注释写回走导出烘焙，不写 OFD）
            annotationService.markPdf(fileId);

            // 导入 PDF 内已有的批注到注释缓存（前端打开后会自动加载显示）
            try {
                Map<Integer, List<AnnotationDTO>> existing =
                        pdfNativeService.parseExistingAnnotations(fileBytes);
                if (!existing.isEmpty()) {
                    annotationService.initFromOfd(fileId, existing);
                    annotationService.markPdf(fileId);
                    log.info("导入PDF已有批注: fileId={}, 共{}页有批注", fileId, existing.size());
                }
            } catch (Exception e) {
                log.warn("导入PDF已有批注失败（忽略）: {}", e.getMessage());
            }

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("原生解析PDF失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("解析失败: " + e.getMessage());
        }
    }

    /**
     * 合并两个 OFD：第一个文件的全部页面在前，第二个在后（当前仅支持 2 个文件）
     */
    @PostMapping("/merge")
    public ResponseEntity<?> mergeOfd(
            @RequestParam("first") MultipartFile first,
            @RequestParam("second") MultipartFile second) {
        try {
            if (first.isEmpty() || second.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择两个 OFD 文件");
            }
            String name1 = first.getOriginalFilename();
            String name2 = second.getOriginalFilename();
            if (!isOfdFilename(name1) || !isOfdFilename(name2)) {
                return ResponseEntity.badRequest().body("请上传 OFD 格式文件");
            }

            log.info("收到合并请求: {} ({} bytes) + {} ({} bytes)",
                    name1, first.getSize(), name2, second.getSize());

            byte[] bytes1 = first.getBytes();
            byte[] bytes2 = second.getBytes();
            byte[] merged = mergeService.mergeTwoOfd(bytes1, bytes2);

            String fileId = UUID.randomUUID().toString();
            cacheService.put(fileId, merged);

            String mergedTitle = buildMergeTitle(name1, name2);
            OfdDocumentDTO result = parseService.parseOfdBytes(merged, mergedTitle + ".ofd");
            result.setFileId(fileId);
            result.setTitle(mergedTitle);

            try {
                Map<Integer, List<AnnotationDTO>> mergedAnnotations =
                        parseService.parseAnnotations(merged);
                annotationService.initFromOfd(fileId, mergedAnnotations);
            } catch (Exception e) {
                log.warn("合并后解析注释层失败: {}", e.getMessage());
            }

            log.info("OFD 合并成功: fileId={}, 共 {} 页", fileId, result.getPageCount());
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("合并 OFD 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("合并失败: " + e.getMessage());
        }
    }

    private static boolean isOfdFilename(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".ofd");
    }

    private static String buildMergeTitle(String name1, String name2) {
        String base1 = stripOfdExt(name1);
        String base2 = stripOfdExt(name2);
        return base1 + "_合并_" + base2;
    }

    private static String stripOfdExt(String filename) {
        if (filename == null) return "文档";
        String n = filename.trim();
        if (n.toLowerCase().endsWith(".ofd")) {
            n = n.substring(0, n.length() - 4);
        }
        return n.isEmpty() ? "文档" : n;
    }

    /**
     * 合并两个 PDF（PDF 原生拼接，第一个文件页面在前）
     * 返回合并后的 PDF 文件供浏览器下载，再由前端决定是否导入为 OFD 编辑。
     */
    @PostMapping("/merge-pdf")
    public ResponseEntity<?> mergePdf(
            @RequestParam("first") MultipartFile first,
            @RequestParam("second") MultipartFile second) {
        try {
            if (first.isEmpty() || second.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择两个 PDF 文件");
            }
            String name1 = first.getOriginalFilename();
            String name2 = second.getOriginalFilename();
            if (!isPdfFilename(name1) || !isPdfFilename(name2)) {
                return ResponseEntity.badRequest().body("请上传 PDF 格式文件");
            }

            log.info("收到 PDF 合并请求: {} + {}", name1, name2);
            byte[] merged = pdfMergeService.mergeTwoPdf(first.getBytes(), second.getBytes());
            String filename = URLEncoder.encode(
                    buildMergePdfFilename(name1, name2),
                    StandardCharsets.UTF_8);

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(merged);

        } catch (Exception e) {
            log.error("合并 PDF 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("合并失败: " + e.getMessage());
        }
    }

    /**
     * 拆分当前缓存中的 OFD（按页码拆成两份，打包为二进制供前端解包下载）
     */
    @PostMapping("/split-ofd")
    public ResponseEntity<?> splitOfd(@RequestBody SplitOfdRequest request) {
        try {
            if (request.getFileId() == null || request.getFileId().isBlank()) {
                return ResponseEntity.badRequest().body("缺少 fileId，请先打开 OFD 文件");
            }
            byte[] cached = cacheService.get(request.getFileId());
            if (cached == null) {
                return ResponseEntity.badRequest().body("文件缓存已失效，请重新打开 OFD 后再拆分");
            }

            OfdSplitService.SplitPair pair =
                    splitService.split(cached, request.getSplitAfterPage());
            String base = request.getTitle() != null ? request.getTitle() : "文档";
            String name1 = SplitPayloadUtil.buildPartFilename(base, 1, "ofd");
            String name2 = SplitPayloadUtil.buildPartFilename(base, 2, "ofd");
            byte[] payload = SplitPayloadUtil.pack(name1, pair.part1(), name2, pair.part2());

            log.info("OFD 拆分成功: fileId={}, 拆分点={}", request.getFileId(), request.getSplitAfterPage());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .body(payload);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("拆分 OFD 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("拆分失败: " + e.getMessage());
        }
    }

    /**
     * 拆分 PDF（需上传原生 PDF，非编辑器内栅格化 OFD）
     */
    @PostMapping("/split-pdf")
    public ResponseEntity<?> splitPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("splitAfterPage") int splitAfterPage) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择 PDF 文件");
            }
            String filename = file.getOriginalFilename();
            if (!isPdfFilename(filename)) {
                return ResponseEntity.badRequest().body("请上传 PDF 格式文件");
            }

            PdfMergeService.SplitPair pair =
                    pdfMergeService.splitPdf(file.getBytes(), splitAfterPage);
            String base = stripPdfExt(filename);
            String name1 = SplitPayloadUtil.buildPartFilename(base, 1, "pdf");
            String name2 = SplitPayloadUtil.buildPartFilename(base, 2, "pdf");
            byte[] payload = SplitPayloadUtil.pack(name1, pair.part1(), name2, pair.part2());

            log.info("PDF 拆分成功: {}, 拆分点={}", filename, splitAfterPage);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .body(payload);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("拆分 PDF 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("拆分失败: " + e.getMessage());
        }
    }

    /** 读取 PDF 页数（用于拆分对话框校验） */
    @PostMapping("/pdf-page-count")
    public ResponseEntity<?> pdfPageCount(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("请选择 PDF 文件");
            }
            if (!isPdfFilename(file.getOriginalFilename())) {
                return ResponseEntity.badRequest().body("请上传 PDF 格式文件");
            }
            int count = pdfMergeService.countPages(file.getBytes());
            return ResponseEntity.ok(Map.of("pageCount", count));
        } catch (Exception e) {
            log.error("读取 PDF 页数失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("读取页数失败: " + e.getMessage());
        }
    }

    private static boolean isPdfFilename(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }

    private static String buildMergePdfFilename(String name1, String name2) {
        String base1 = stripPdfExt(name1);
        String base2 = stripPdfExt(name2);
        return base1 + "_合并_" + base2 + ".pdf";
    }

    private static String stripPdfExt(String filename) {
        if (filename == null) return "文档";
        String n = filename.trim();
        if (n.toLowerCase().endsWith(".pdf")) {
            n = n.substring(0, n.length() - 4);
        }
        return n.isEmpty() ? "文档" : n;
    }

    /**
     * 保存编辑后的OFD（含注释）
     */
    @PostMapping("/save")
    public ResponseEntity<?> saveOfd(@RequestBody OfdDocumentDTO documentDTO) {
        try {
            log.info("收到保存请求: {}, fileId: {}", documentDTO.getTitle(), documentDTO.getFileId());

            byte[] originalOfd = null;
            if (documentDTO.getFileId() != null) {
                originalOfd = cacheService.get(documentDTO.getFileId());
            }

            if (originalOfd == null) {
                log.warn("原始OFD缓存不存在或已过期, fileId={}, 拒绝降级重建以避免内容丢失",
                        documentDTO.getFileId());
                return ResponseEntity.badRequest().body(
                        "文件缓存已失效（可能是后端重启或会话超时），请重新上传原始OFD后再保存。");
            }

            byte[] ofdBytes = rebuildService.rebuildOfd(documentDTO, originalOfd);

            if (documentDTO.getFileId() != null) {
                cacheService.put(documentDTO.getFileId(), ofdBytes);
            }

            String filename = URLEncoder.encode(
                    (documentDTO.getTitle() != null ? documentDTO.getTitle() : "edited") + ".ofd",
                    StandardCharsets.UTF_8
            );

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/ofd")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(ofdBytes);

        } catch (Exception e) {
            log.error("保存OFD失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("保存失败: " + e.getMessage());
        }
    }

    // ==================== 注释相关接口 ====================

    /**
     * 获取某页注释
     * GET /api/ofd/{fileId}/annotations?pageIndex=0
     */
    @GetMapping("/{fileId}/annotations")
    public ResponseEntity<?> getAnnotations(
            @PathVariable("fileId") String fileId,
            @RequestParam("pageIndex") Integer pageIndex) {
        try {
            List<AnnotationDTO> annotations = annotationService.getAnnotations(fileId, pageIndex);
            log.info("获取注释: fileId={}, pageIndex={}, count={}", fileId, pageIndex, annotations.size());
            return ResponseEntity.ok(annotations);
        } catch (Exception e) {
            log.error("获取注释失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("获取注释失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有页注释
     * GET /api/ofd/{fileId}/annotations/all
     */
    @GetMapping("/{fileId}/annotations/all")
    public ResponseEntity<?> getAllAnnotations(@PathVariable("fileId") String fileId) {
        try {
            Map<Integer, List<AnnotationDTO>> all = annotationService.getAllAnnotations(fileId);
            log.info("获取所有注释: fileId={}, 共{}页", fileId, all.size());
            return ResponseEntity.ok(all);
        } catch (Exception e) {
            log.error("获取所有注释失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("获取注释失败: " + e.getMessage());
        }
    }

    /**
     * 新增注释
     * POST /api/ofd/{fileId}/annotations
     */
    @PostMapping("/{fileId}/annotations")
    public ResponseEntity<?> addAnnotation(
            @PathVariable("fileId") String fileId,
            @RequestBody AnnotationDTO annotation) {
        try {
            log.info("新增注释: fileId={}, type={}, pageIndex={}",
                    fileId, annotation.getType(), annotation.getPageIndex());
            AnnotationDTO saved = annotationService.addAnnotation(fileId, annotation);
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("新增注释失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("新增注释失败: " + e.getMessage());
        }
    }

    /**
     * 更新注释
     * PUT /api/ofd/{fileId}/annotations/{annotationId}
     */
    @PutMapping("/{fileId}/annotations/{annotationId}")
    public ResponseEntity<?> updateAnnotation(
            @PathVariable("fileId") String fileId,
            @PathVariable("annotationId") String annotationId,
            @RequestBody AnnotationDTO annotation) {
        try {
            log.info("更新注释: fileId={}, annotationId={}", fileId, annotationId);
            AnnotationDTO updated = annotationService.updateAnnotation(fileId, annotationId, annotation);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.error("更新注释失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("更新注释失败: " + e.getMessage());
        }
    }

    /**
     * 删除单条注释
     * DELETE /api/ofd/{fileId}/annotations/{annotationId}
     */
    @DeleteMapping("/{fileId}/annotations/{annotationId}")
    public ResponseEntity<?> deleteAnnotation(
            @PathVariable("fileId") String fileId,
            @PathVariable("annotationId") String annotationId) {
        try {
            log.info("删除注释: fileId={}, annotationId={}", fileId, annotationId);
            annotationService.deleteAnnotation(fileId, annotationId);
            return ResponseEntity.ok("删除成功");
        } catch (Exception e) {
            log.error("删除注释失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("删除注释失败: " + e.getMessage());
        }
    }

    /**
     * 删除某页所有注释
     * DELETE /api/ofd/{fileId}/annotations?pageIndex=0
     */
    @DeleteMapping("/{fileId}/annotations")
    public ResponseEntity<?> deleteAllAnnotations(
            @PathVariable("fileId") String fileId,
            @RequestParam("pageIndex") Integer pageIndex) {
        try {
            log.info("删除整页注释: fileId={}, pageIndex={}", fileId, pageIndex);
            annotationService.deleteAllAnnotations(fileId, pageIndex);
            return ResponseEntity.ok("删除成功");
        } catch (Exception e) {
            log.error("删除整页注释失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("删除失败: " + e.getMessage());
        }
    }

    /**
     * 导出含注释的OFD文件
     * GET /api/ofd/{fileId}/export
     */
    @GetMapping("/{fileId}/export")
    public ResponseEntity<?> exportWithAnnotations(@PathVariable("fileId") String fileId) {
        try {
            log.info("导出含注释OFD: fileId={}", fileId);

            byte[] originalOfd = cacheService.get(fileId);
            if (originalOfd == null) {
                return ResponseEntity.badRequest().body("文件缓存已过期，请重新上传");
            }

            // 取出所有注释，写回OFD
            Map<Integer, List<AnnotationDTO>> allAnnotations =
                    annotationService.getAllAnnotations(fileId);
            byte[] ofdBytes = rebuildService.rebuildWithAnnotations(originalOfd, allAnnotations);

            String filename = URLEncoder.encode("annotated.ofd", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/ofd")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(ofdBytes);

        } catch (Exception e) {
            log.error("导出含注释OFD失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("导出失败: " + e.getMessage());
        }
    }

    /**
     * 导出含注释的 PDF（原生 PDF 文档）：把注释非破坏地烘焙回原 PDF，
     * 并按前端给出的页面布局重建页序（支持重排/删除/插入空白/复制页）。
     * POST /api/ofd/{fileId}/export-pdf
     *
     * body 可空：为空时按原序、用服务端缓存的注释烘焙。
     */
    @PostMapping("/{fileId}/export-pdf")
    public ResponseEntity<?> exportPdfWithAnnotations(
            @PathVariable("fileId") String fileId,
            @RequestBody(required = false) PdfExportRequest request) {
        try {
            log.info("导出含注释PDF: fileId={}", fileId);

            byte[] originalPdf = cacheService.get(fileId);
            if (originalPdf == null) {
                return ResponseEntity.badRequest().body("文件缓存已过期，请重新上传");
            }

            byte[] pdfBytes;
            if (request != null && request.getPages() != null && !request.getPages().isEmpty()) {
                pdfBytes = pdfNativeService.exportWithLayout(originalPdf, request);
            } else {
                Map<Integer, List<AnnotationDTO>> allAnnotations =
                        annotationService.getAllAnnotations(fileId);
                pdfBytes = pdfNativeService.bakeAnnotations(originalPdf, allAnnotations);
            }

            String filename = URLEncoder.encode("annotated.pdf", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(pdfBytes);

        } catch (Exception e) {
            log.error("导出含注释PDF失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("导出失败: " + e.getMessage());
        }
    }

    // ==================== 格式转换 ====================

    @PostMapping("/to-pdf")
    public ResponseEntity<?> convertToPdf(@RequestParam("file") MultipartFile file) {
        try {
            byte[] pdfBytes = conversionService.ofdToPdf(file);
            String filename = URLEncoder.encode(
                    getNameWithoutExt(file.getOriginalFilename()) + ".pdf",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(pdfBytes);
        } catch (Exception e) {
            log.error("OFD转PDF失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("转换失败: " + e.getMessage());
        }
    }

    @PostMapping("/from-pdf")
    public ResponseEntity<?> convertFromPdf(@RequestParam("file") MultipartFile file) {
        try {
            byte[] ofdBytes = conversionService.pdfToOfd(file);
            String filename = URLEncoder.encode(
                    getNameWithoutExt(file.getOriginalFilename()) + ".ofd",
                    StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/ofd")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + filename)
                    .body(ofdBytes);
        } catch (Exception e) {
            log.error("PDF转OFD失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("转换失败: " + e.getMessage());
        }
    }

    private String getNameWithoutExt(String filename) {
        if (filename == null) return "output";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}