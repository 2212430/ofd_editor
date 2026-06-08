package com.ofdeditor.dto;

import lombok.Data;

@Data
public class SplitOfdRequest {
    /** 当前打开 OFD 的缓存 fileId */
    private String fileId;
    /** 第一部分包含的最后一页页码（1-based） */
    private int splitAfterPage;
    /** 用于生成「XXX第一部分.ofd」的文件名基底 */
    private String title;
}
