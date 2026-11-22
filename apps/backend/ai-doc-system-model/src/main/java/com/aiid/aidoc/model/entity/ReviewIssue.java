package com.aiid.aidoc.model.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("review_issue")
public class ReviewIssue {
    @TableId
    private String id;
    private String recordId;
    private String severity;
    private String dimension;
    private String conflictType;
    private String reviewScope;
    private String sourceClauseNumber;
    private String standardId;
    private String clauseId;
    private BigDecimal confidence;
    private String reviewComment;
    private String title;
    private String description;
    private String originalSnippet;
    private Integer snippetStart;
    private String chapterRef;
    private String standardRef;
    private String standardClause;
    private String standardText;
    private String suggestionText;
    private String status;
    private LocalDateTime createdAt;
}
