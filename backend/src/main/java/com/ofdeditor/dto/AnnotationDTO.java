package com.ofdeditor.dto;

/**
 * 注释数据传输对象
 * 对应前端 AnnotationData 接口
 */
public class AnnotationDTO {

    /** 注释唯一ID */
    private String id;

    /**
     * 注释类型:
     * HIGHLIGHT   - 高亮
     * UNDERLINE   - 下划线
     * STRIKEOUT   - 删除线
     * TEXTBOX     - 文本框
     * STICKYNOTE  - 便利贴
     * ARROW       - 箭头
     * CIRCLE      - 圆形
     * RECTANGLE   - 矩形
     * FREEHAND    - 手绘
     * STAMP       - 图章
     */
    private String type;

    /** 所在页面索引（从0开始） */
    private Integer pageIndex;

    /** X坐标（mm，相对页面左上角） */
    private Double x;

    /** Y坐标（mm，相对页面左上角） */
    private Double y;

    /** 宽度（mm） */
    private Double width;

    /** 高度（mm） */
    private Double height;

    /** 填充颜色，格式 "#RRGGBB" */
    private String color;

    /** 透明度 0.0 ~ 1.0 */
    private Double opacity;

    /** 文本内容（TEXTBOX / STICKYNOTE 使用） */
    private String content;

    /** 字体大小（TEXTBOX / STICKYNOTE 使用） */
    private Double fontSize;

    /** 字体颜色（TEXTBOX / STICKYNOTE 使用） */
    private String fontColor;

    /** 边框颜色 */
    private String strokeColor;

    /** 边框宽度（mm） */
    private Double lineWidth;

    /**
     * 手绘路径点列表，格式：
     * [[x1,y1],[x2,y2],...] 序列化为JSON字符串存储
     * （FREEHAND 使用）
     */
    private String pathPoints;

    /** 图章图片Base64（STAMP 使用） */
    private String stampBase64;

    /** 创建时间戳 */
    private Long createdAt;

    /** 最后修改时间戳 */
    private Long updatedAt;

    /** 是否在编辑器中隐藏（不写入 OFD 语义，仅会话内管理） */
    private Boolean hidden;

    // ==================== Getters & Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Integer getPageIndex() { return pageIndex; }
    public void setPageIndex(Integer pageIndex) { this.pageIndex = pageIndex; }

    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }

    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }

    public Double getWidth() { return width; }
    public void setWidth(Double width) { this.width = width; }

    public Double getHeight() { return height; }
    public void setHeight(Double height) { this.height = height; }

    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }

    public Double getOpacity() { return opacity; }
    public void setOpacity(Double opacity) { this.opacity = opacity; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Double getFontSize() { return fontSize; }
    public void setFontSize(Double fontSize) { this.fontSize = fontSize; }

    public String getFontColor() { return fontColor; }
    public void setFontColor(String fontColor) { this.fontColor = fontColor; }

    public String getStrokeColor() { return strokeColor; }
    public void setStrokeColor(String strokeColor) { this.strokeColor = strokeColor; }

    public Double getLineWidth() { return lineWidth; }
    public void setLineWidth(Double lineWidth) { this.lineWidth = lineWidth; }

    public String getPathPoints() { return pathPoints; }
    public void setPathPoints(String pathPoints) { this.pathPoints = pathPoints; }

    public String getStampBase64() { return stampBase64; }
    public void setStampBase64(String stampBase64) { this.stampBase64 = stampBase64; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public Boolean getHidden() { return hidden; }
    public void setHidden(Boolean hidden) { this.hidden = hidden; }

    @Override
    public String toString() {
        return "AnnotationDTO{" +
                "id='" + id + '\'' +
                ", type='" + type + '\'' +
                ", pageIndex=" + pageIndex +
                ", x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                '}';
    }
}