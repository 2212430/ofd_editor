package com.ofdeditor.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 原生 PDF 导出请求：由前端给出「当前页面布局」+「注释」，
 * 后端据此从缓存的原始 PDF 重建页序（支持重排/删除/插入空白页/复制页），
 * 再把注释非破坏地烘焙到对应页。
 */
@Data
public class PdfExportRequest {

    /** 按当前编辑器顺序排列的页面 */
    private List<PageLayout> pages;

    /** 注释：key = 当前页序号（与 pages 下标一致），value = 该页注释 */
    private Map<Integer, List<AnnotationDTO>> annotations;

    /** 全局水印（可空） */
    private WatermarkDTO watermark;

    @Data
    public static class PageLayout {
        /** 原始 PDF 页号（0 基）；为 null 表示新插入的空白页 */
        private Integer sourceIndex;
        /** 页宽（mm，空白页用） */
        private Double widthMm;
        /** 页高（mm，空白页用） */
        private Double heightMm;
        /** 单页额外旋转（度，0/90/180/270），在原始 /Rotate 基础上叠加并持久化 */
        private Integer rotate;
    }
}
