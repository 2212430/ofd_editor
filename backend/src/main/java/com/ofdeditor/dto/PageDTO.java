package com.ofdeditor.dto;

import lombok.Data;
import java.util.List;

@Data
public class PageDTO {
    private Integer pageIndex;
    /** 对应原始 OFD 中的页序号（0 基），用于保存时重排/复制/删除 */
    private Integer sourcePageIndex;
    private Double width;
    private Double height;
    private List<ElementDTO> elements;
    private String pageBackgroundBase64;
}