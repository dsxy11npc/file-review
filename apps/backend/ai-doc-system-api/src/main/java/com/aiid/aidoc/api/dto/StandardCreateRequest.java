package com.aiid.aidoc.api.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class StandardCreateRequest {
    private String number;
    private String name;
    private String profession;
    private String category;
    private LocalDate effectiveDate;
    private LocalDate expiryDate;
    private String status;
    private String supersededBy;
    private String description;
}
