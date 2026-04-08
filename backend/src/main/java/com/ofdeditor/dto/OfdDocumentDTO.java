package com.ofdeditor.dto;

import lombok.Data;
import java.util.List;

@Data
public class OfdDocumentDTO {
    private String title;
    private String author;
    private Integer pageCount;
    private List<PageDTO> pages;
    private String fileId;
}