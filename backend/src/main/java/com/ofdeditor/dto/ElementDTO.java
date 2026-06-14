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
    /**
     * 是否为竖排文本：来源于 TextCode 的 {@code DeltaY}（且 {@code DeltaX} 为空/全 0）。
     * 前端按此走多行渲染：每个字符独立成行、字号取 {@code Size} 而非外接框高度。
     */
    private Boolean verticalLayout;
    /** 密码区网格排版（{@code g N} 语法），前端禁用自动换行并按行距渲染 */
    private Boolean passwordGrid;
    /** TextCode DeltaX 平均字距（mm），供前端还原 OFD 逐字排版 */
    private Double glyphAdvanceMm;

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
    /** 用户新插入的元素（如导入图片），保存时需写入 OFD 而非匹配已有 XML 节点 */
    private Boolean isNew;
    // 是否被修改
    private Boolean isDirty;
    /** 标记删除：保存时从原 OFD 页面 XML 移除匹配节点 */
    private Boolean isDeleted;
    /** 图片像素内容已变更（裁剪/替换），保存时需写回 Res 二进制 */
    private Boolean imageContentDirty;

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