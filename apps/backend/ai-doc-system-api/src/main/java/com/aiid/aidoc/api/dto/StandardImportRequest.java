package com.aiid.aidoc.api.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class StandardImportRequest {
    private String number;
    private String name;
    private String profession;
    private String category;
    private String status;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String description;
    private String content;
}
