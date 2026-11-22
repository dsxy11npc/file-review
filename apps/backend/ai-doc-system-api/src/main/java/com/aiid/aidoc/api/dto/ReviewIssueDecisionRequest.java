package com.aiid.aidoc.api.dto;

import lombok.Data;

@Data
public class ReviewIssueDecisionRequest {
    private String status;
    private String reviewComment;
}
