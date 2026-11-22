package com.aiid.aidoc.controller;

import com.aiid.aidoc.api.dto.ConflictReviewCreateRequest;
import com.aiid.aidoc.api.dto.PaginationResponse;
import com.aiid.aidoc.api.dto.ReviewIssueDecisionRequest;
import com.aiid.aidoc.model.common.ApiResponse;
import com.aiid.aidoc.model.entity.ReviewIssue;
import com.aiid.aidoc.model.entity.ReviewRecord;
import com.aiid.aidoc.service.ConflictReviewService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/conflict-reviews")
@RequiredArgsConstructor
public class ConflictReviewController {
    private final ConflictReviewService conflictReviewService;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody ConflictReviewCreateRequest body,
                                                   HttpServletRequest request) {
        try {
            ReviewRecord record = conflictReviewService.create(userId(request), body);
            return ApiResponse.created(Map.of("id", record.getId(), "status", record.getStatus()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.message(400, e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String id, HttpServletRequest request) {
        ReviewRecord record = conflictReviewService.getOwnedRecord(id, userId(request));
        if (record == null) {
            return ApiResponse.message(404, "审查记录不存在");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", record.getId());
        result.put("documentName", record.getDocumentName());
        result.put("content", record.getContent());
        result.put("reviewType", record.getReviewType());
        result.put("baselineDate", record.getBaselineDate());
        result.put("clauseCount", record.getClauseCount());
        result.put("engineVersion", record.getEngineVersion());
        result.put("status", record.getStatus());
        result.put("createdAt", record.getCreatedAt());
        result.put("updatedAt", record.getUpdatedAt());
        result.put("standardIds", parseList(record.getStandardIds()));
        result.put("replacedStandardIds", parseList(record.getReplacedStandardIds()));
        result.put("summary", parseMap(record.getSummary()));
        result.put("issues", conflictReviewService.getIssues(id));
        return ApiResponse.success(result);
    }

    @GetMapping
    public ApiResponse<PaginationResponse<ReviewRecord>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            HttpServletRequest request) {
        Page<ReviewRecord> result = conflictReviewService.list(userId(request), page, pageSize);
        PaginationResponse<ReviewRecord> response = new PaginationResponse<>();
        response.setTotal(result.getTotal());
        response.setPage(result.getCurrent());
        response.setPageSize(result.getSize());
        response.setList(result.getRecords());
        return ApiResponse.success(response);
    }

    @PutMapping("/{recordId}/issues/{issueId}")
    public ApiResponse<ReviewIssue> decide(@PathVariable String recordId,
                                           @PathVariable String issueId,
                                           @RequestBody ReviewIssueDecisionRequest body,
                                           HttpServletRequest request) {
        try {
            ReviewIssue issue = conflictReviewService.decide(recordId, issueId, userId(request),
                    body.getStatus(), body.getReviewComment());
            if (issue == null) {
                return ApiResponse.message(404, "审查问题不存在");
            }
            return ApiResponse.success(issue);
        } catch (IllegalArgumentException e) {
            return ApiResponse.message(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        if (!conflictReviewService.delete(id, userId(request))) {
            return ApiResponse.message(404, "审查记录不存在");
        }
        return ApiResponse.message(200, "已删除");
    }

    private String userId(HttpServletRequest request) {
        return (String) request.getAttribute("userId");
    }

    private List<String> parseList(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "[]" : value,
                    new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String value) {
        try {
            return objectMapper.readValue(value == null || value.isBlank() ? "{}" : value,
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }
}
