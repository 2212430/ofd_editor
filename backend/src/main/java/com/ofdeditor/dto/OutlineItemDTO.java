package com.ofdeditor.dto;

import lombok.Data;

import java.util.List;

/** 文档大纲（书签）节点 */
@Data
public class OutlineItemDTO {
    private String title;
    /** 跳转目标页（0 基），可为空 */
    private Integer pageIndex;
    /** 外部链接（URI 动作），可为空 */
    private String uri;
    private List<OutlineItemDTO> children;
}
