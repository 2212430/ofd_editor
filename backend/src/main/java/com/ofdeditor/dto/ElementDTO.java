package com.ofdeditor.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

@Data
public class ElementDTO {
    private String id;
    // OFD 内部对象ID（用于精确回写匹配）
    private String xmlObjId;
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

    // ========== PATH相关（OFD 矢量 PathObject，对应 AbbreviatedData -> SVG d）==========
    // PATH数据（SVG path d）
    private String pathData;
    // 填充颜色
    private String fillColor;
    // 描边颜色
    private String strokeColor;
    // 线宽
    private Double lineWidth;
    /** 与 OFD 属性 {@code Fill} 一致；false 表示不填充、仅描边或透明 */
    private Boolean pathFillEnabled;
    /** 与 OFD 属性 {@code Stroke} 一致；false 表示不描边、仅填充 */
    private Boolean pathStrokeEnabled;

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