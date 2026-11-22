package com.aiid.aidoc.service;

import com.aiid.aidoc.api.dto.ConflictReviewCreateRequest;
import com.aiid.aidoc.model.entity.ReviewIssue;
import com.aiid.aidoc.model.entity.ReviewRecord;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;

public interface ConflictReviewService {
    ReviewRecord create(String userId, ConflictReviewCreateRequest request);

    ReviewRecord getOwnedRecord(String id, String userId);

    List<ReviewIssue> getIssues(String recordId);

    Page<ReviewRecord> list(String userId, long page, long pageSize);

    ReviewIssue decide(String recordId, String issueId, String userId, String status, String comment);

    boolean delete(String id, String userId);
}
