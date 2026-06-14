package com.ofdeditor.service;

import com.ofdeditor.dto.AnnotationDTO;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 注释业务逻辑服务
 * 负责注释的增删改查，以及调用 OfdRebuildService 写回 OFD 文件
 */
@Service
public class AnnotationService {

    /**
     * 内存缓存：fileId -> pageIndex -> List<AnnotationDTO>
     * 生产环境可替换为数据库存储
     */
    private final Map<String, Map<Integer, List<AnnotationDTO>>> cache =
            new ConcurrentHashMap<>();

    /** 标记为原生 PDF 的 fileId：注释只存缓存，导出时由 PdfNativeService 烘焙，不写回 OFD */
    private final Set<String> pdfFileIds = ConcurrentHashMap.newKeySet();

    private final OfdRebuildService ofdRebuildService;

    public AnnotationService(OfdRebuildService ofdRebuildService) {
        this.ofdRebuildService = ofdRebuildService;
    }

    /** 标记某文件为原生 PDF，跳过 OFD 写回逻辑 */
    public void markPdf(String fileId) {
        pdfFileIds.add(fileId);
        cache.computeIfAbsent(fileId, k -> new ConcurrentHashMap<>());
    }

    public boolean isPdf(String fileId) {
        return pdfFileIds.contains(fileId);
    }

    // ==================== 查询 ====================

    /**
     * 获取某文件某页的所有注释
     */
    public List<AnnotationDTO> getAnnotations(String fileId, Integer pageIndex) {
        return cache
                .getOrDefault(fileId, Collections.emptyMap())
                .getOrDefault(pageIndex, Collections.emptyList());
    }

    /**
     * 获取某文件所有页的注释
     * key = pageIndex, value = 注释列表
     */
    public Map<Integer, List<AnnotationDTO>> getAllAnnotations(String fileId) {
        return cache.getOrDefault(fileId, Collections.emptyMap());
    }

    // ==================== 新增 ====================

    /**
     * 添加一条注释并写回 OFD
     *
     * @param fileId        文件ID
     * @param annotation    注释数据
     * @return              带 id/createdAt 的注释数据
     */
    public AnnotationDTO addAnnotation(String fileId, AnnotationDTO annotation) {
        // 1. 补全元数据
        if (annotation.getId() == null || annotation.getId().isEmpty()) {
            annotation.setId(UUID.randomUUID().toString());
        }
        long now = System.currentTimeMillis();
        annotation.setCreatedAt(now);
        annotation.setUpdatedAt(now);

        // 2. 写入缓存
        cache.computeIfAbsent(fileId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(annotation.getPageIndex(), k -> new ArrayList<>())
                .add(annotation);

        // 3. 写回 OFD Annotation 层（原生 PDF 跳过，导出时统一烘焙）
        if (!isPdf(fileId)) {
            try {
                ofdRebuildService.writeAnnotationToOfd(fileId, annotation);
            } catch (Exception e) {
                // 写回失败不影响缓存，记录日志即可
                System.err.println("[AnnotationService] 写回OFD失败: " + e.getMessage());
            }
        }

        return annotation;
    }

    // ==================== 批量新增 ====================

    /**
     * 批量添加注释（如导入场景）
     */
    public List<AnnotationDTO> addAnnotations(String fileId, List<AnnotationDTO> annotations) {
        return annotations.stream()
                .map(ann -> addAnnotation(fileId, ann))
                .collect(Collectors.toList());
    }

    // ==================== 修改 ====================

    /**
     * 更新一条注释并写回 OFD
     */
    public AnnotationDTO updateAnnotation(String fileId, String annotationId,
                                          AnnotationDTO updated) {
        Map<Integer, List<AnnotationDTO>> pageMap = cache.get(fileId);
        if (pageMap == null) {
            throw new NoSuchElementException("文件不存在: " + fileId);
        }

        // 遍历所有页找到目标注释
        for (List<AnnotationDTO> list : pageMap.values()) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getId().equals(annotationId)) {
                    AnnotationDTO existing = list.get(i);
                    AnnotationDTO merged = mergeAnnotation(existing, updated, annotationId);
                    list.set(i, merged);

                    // 写回 OFD（原生 PDF 跳过）
                    if (!isPdf(fileId)) {
                        try {
                            ofdRebuildService.updateAnnotationInOfd(fileId, existing, merged);
                        } catch (Exception e) {
                            System.err.println("[AnnotationService] 更新OFD注释失败: " + e.getMessage());
                        }
                    }

                    return merged;
                }
            }
        }

        throw new NoSuchElementException("注释不存在: " + annotationId);
    }

    // ==================== 删除 ====================

    /**
     * 删除单条注释并从 OFD 移除
     */
    public void deleteAnnotation(String fileId, String annotationId) {
        Map<Integer, List<AnnotationDTO>> pageMap = cache.get(fileId);
        if (pageMap == null) {
            throw new NoSuchElementException("文件不存在: " + fileId);
        }

        boolean removed = false;
        for (List<AnnotationDTO> list : pageMap.values()) {
            Iterator<AnnotationDTO> it = list.iterator();
            while (it.hasNext()) {
                if (it.next().getId().equals(annotationId)) {
                    it.remove();
                    removed = true;
                    break;
                }
            }
            if (removed) break;
        }

        if (!removed) {
            throw new NoSuchElementException("注释不存在: " + annotationId);
        }

        // 从 OFD 移除（原生 PDF 跳过）
        if (!isPdf(fileId)) {
            try {
                ofdRebuildService.removeAnnotationFromOfd(fileId, annotationId);
            } catch (Exception e) {
                System.err.println("[AnnotationService] 从OFD删除注释失败: " + e.getMessage());
            }
        }
    }

    /**
     * 删除某文件某页所有注释
     */
    public void deleteAllAnnotations(String fileId, Integer pageIndex) {
        Map<Integer, List<AnnotationDTO>> pageMap = cache.get(fileId);
        if (pageMap != null) {
            pageMap.remove(pageIndex);
        }

        if (!isPdf(fileId)) {
            try {
                ofdRebuildService.removeAllAnnotationsFromOfd(fileId, pageIndex);
            } catch (Exception e) {
                System.err.println("[AnnotationService] 从OFD批量删除注释失败: " + e.getMessage());
            }
        }
    }

    // ==================== 缓存管理 ====================

    /**
     * 文件关闭时清理缓存
     */
    public void clearCache(String fileId) {
        cache.remove(fileId);
        pdfFileIds.remove(fileId);
    }

    /**
     * 从 OFD 文件初始化注释缓存（文件打开时调用）
     */
    public void initFromOfd(String fileId, Map<Integer, List<AnnotationDTO>> annotationsFromOfd) {
        cache.put(fileId, new ConcurrentHashMap<>(annotationsFromOfd));
    }

    private AnnotationDTO mergeAnnotation(AnnotationDTO existing, AnnotationDTO patch, String annotationId) {
        AnnotationDTO merged = new AnnotationDTO();
        merged.setId(annotationId);
        merged.setCreatedAt(existing.getCreatedAt());
        merged.setUpdatedAt(System.currentTimeMillis());

        merged.setType(firstNonNull(patch.getType(), existing.getType()));
        merged.setPageIndex(firstNonNull(patch.getPageIndex(), existing.getPageIndex()));
        merged.setX(firstNonNull(patch.getX(), existing.getX()));
        merged.setY(firstNonNull(patch.getY(), existing.getY()));
        merged.setWidth(firstNonNull(patch.getWidth(), existing.getWidth()));
        merged.setHeight(firstNonNull(patch.getHeight(), existing.getHeight()));
        merged.setColor(firstNonNull(patch.getColor(), existing.getColor()));
        merged.setOpacity(firstNonNull(patch.getOpacity(), existing.getOpacity()));
        merged.setContent(firstNonNull(patch.getContent(), existing.getContent()));
        merged.setFontSize(firstNonNull(patch.getFontSize(), existing.getFontSize()));
        merged.setFontColor(firstNonNull(patch.getFontColor(), existing.getFontColor()));
        merged.setStrokeColor(firstNonNull(patch.getStrokeColor(), existing.getStrokeColor()));
        merged.setLineWidth(firstNonNull(patch.getLineWidth(), existing.getLineWidth()));
        merged.setPathPoints(firstNonNull(patch.getPathPoints(), existing.getPathPoints()));
        merged.setStampBase64(firstNonNull(patch.getStampBase64(), existing.getStampBase64()));
        merged.setHidden(patch.getHidden() != null ? patch.getHidden() : existing.getHidden());
        return merged;
    }

    private <T> T firstNonNull(T a, T b) {
        return a != null ? a : b;
    }
}