package com.aiid.aidoc.api.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class ConflictReviewCreateRequest {
    private String documentName;
    private String content;
    private String reviewType;
    private LocalDate baselineDate;
    private List<String> standardIds;
    private List<String> replacedStandardIds;
}
