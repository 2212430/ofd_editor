package com.ofdeditor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class ElementDTO {
    private String id;
    private Boolean skip;
    // TEXT / IMAGE / PATH
    private String type;

    private Double x;
    private Double y;
    private Double width;
    private Double height;

    // 原始CTM矩阵字符串，如 "1 0 0 1 10 20"（新增）
    private String ctm;

    // ========== 文本相关 ==========
    // 文本内容
    private String content;
    // 字体大小（mm）
    private Double fontSize;
    // 字体颜色，如 #000000
    private String color;
    // 字体名称（新增）
    private String fontFamily;
    // 是否加粗（新增）
    private Boolean bold;
    // 是否斜体（新增）
    private Boolean italic;

    // ========== 变换相关 ==========
    // 旋转角度
    private Double rotation;
    // 缩放
    private Double scaleX;
    private Double scaleY;

    // ========== PATH相关 ==========
    // PATH数据（SVG path d）
    private String pathData;
    // 填充颜色（新增）
    private String fillColor;
    // 描边颜色（新增）
    private String strokeColor;
    // 线宽（新增）
    private Double lineWidth;

    // ========== 图片相关 ==========
    // 图片资源ID
    private String resourceId;
    // 图片Base64（前端优先使用）
    private String imageBase64;
    // 图片URL（可选）
    private String imageUrl;
    // 兼容旧字段（后续前端统一后删除）
    private String imageData;

    // ========== 编辑状态 ==========
    // 是否被修改
    private Boolean isDirty;

    // 原始坐标（非破坏性编辑）
    private Double originalX;
    private Double originalY;
    private Double originalWidth;
    private Double originalHeight;
    private Double originalRotation;
    @JsonIgnore
    public Boolean getSkip() { return skip; }
    public void setSkip(Boolean skip) { this.skip = skip; }
}