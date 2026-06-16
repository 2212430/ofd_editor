package com.ofdeditor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * OFD 验签结果。
 */
@Data
public class SignatureVerifyResult {

    /** 是否包含签名/签章 */
    private boolean signed;

    /** 签名个数 */
    private int count;

    /** 整体是否有效（完整性 + 签名值均通过） */
    private boolean valid;

    /** 概述信息（中文，给前端直接展示） */
    private String message;

    /** 每个签名的细节 */
    private List<Item> signatures = new ArrayList<>();

    @Data
    public static class Item {
        /** 签名 ID */
        private String id;
        /** 类型：Seal（电子印章）/ Sign（数字签名） */
        private String type;
        /** 是否有效 */
        private boolean valid;
        /** 印章/签名者名称（尽力解析，可能为空） */
        private String sealName;
        /** 签署时间（尽力解析，可能为空） */
        private String signDate;
        /** 该签名的说明信息 */
        private String message;
    }
}
