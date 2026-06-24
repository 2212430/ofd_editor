package com.ofdeditor.dto;

import lombok.Data;
import java.util.List;

@Data
public class OfdDocumentDTO {
    private String title;
    private String author;
    private Integer pageCount;
    private List<PageDTO> pages;
    /** 文档大纲（书签），来自 Document.xml Outlines */
    private List<OutlineItemDTO> outlines;
    private String fileId;
    /** 保存时可选全局文本水印 */
    private WatermarkDTO watermark;
}