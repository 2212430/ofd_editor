package com.ofdeditor.controller;

import com.ofdeditor.dto.AnnotationDTO;
import com.ofdeditor.dto.OfdDocumentDTO;
import com.ofdeditor.service.AnnotationService;
import com.ofdeditor.service.ConversionService;
import com.ofdeditor.service.OfdCacheService;
import com.ofdeditor.service.OfdParseService;
import com.ofdeditor.service.OfdRebuildService;
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
    private final ConversionService conversionService;
    private final OfdRebuildService rebuildService;
    private final OfdCacheService cacheService;
    private final AnnotationService annotationService;  // ← 新增

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
            @PathVariable String fileId,
            @RequestParam Integer pageIndex) {
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
    public ResponseEntity<?> getAllAnnotations(@PathVariable String fileId) {
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
            @PathVariable String fileId,
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
            @PathVariable String fileId,
            @PathVariable String annotationId,
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
            @PathVariable String fileId,
            @PathVariable String annotationId) {
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
            @PathVariable String fileId,
            @RequestParam Integer pageIndex) {
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
    public ResponseEntity<?> exportWithAnnotations(@PathVariable String fileId) {
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