package com.ofdeditor.dto;

/**
 * 注释讨论/回复（编辑器会话缓存，不写入 OFD）
 */
public class AnnotationReplyDTO {

    private String id;
    /** 所属注释 ID */
    private String annotationId;
    /** 回复者显示名 */
    private String author;
    /** 回复正文 */
    private String content;
    /** 父回复 ID（为空表示直接回复注释） */
    private String parentReplyId;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getAnnotationId() { return annotationId; }
    public void setAnnotationId(String annotationId) { this.annotationId = annotationId; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getParentReplyId() { return parentReplyId; }
    public void setParentReplyId(String parentReplyId) { this.parentReplyId = parentReplyId; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
