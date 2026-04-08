package com.ofdeditor.dto;

import lombok.Data;
import java.util.List;

@Data
public class PageDTO {
    private Integer pageIndex;
    private Double width;
    private Double height;
    private List<ElementDTO> elements;
    private String pageBackgroundBase64;
}