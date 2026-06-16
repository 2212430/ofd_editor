package com.ofdeditor.dto;

import lombok.Data;

/**
 * 水印参数（文本水印），PDF / OFD 通用。
 */
@Data
public class WatermarkDTO {
    /** 水印文字 */
    private String text;
    /** 字号（pt） */
    private Double fontSize;
    /** 颜色（#RRGGBB） */
    private String color;
    /** 透明度 0~1 */
    private Double opacity;
    /** 旋转角度（度，正值逆时针；典型 -45 或 45） */
    private Double angle;
    /** 是否平铺整页（false 则页面居中单个） */
    private Boolean tile;
    /** 平铺水平间距（pt） */
    private Double gapX;
    /** 平铺垂直间距（pt） */
    private Double gapY;
    /** 是否加粗 */
    private Boolean bold;
}
