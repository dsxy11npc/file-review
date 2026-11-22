package com.aiid.aidoc.service.impl;

import com.aiid.aidoc.api.dto.ConflictReviewCreateRequest;
import com.aiid.aidoc.model.entity.Clause;
import com.aiid.aidoc.model.entity.ReviewIssue;
import com.aiid.aidoc.model.entity.ReviewRecord;
import com.aiid.aidoc.model.entity.Standard;
import com.aiid.aidoc.repository.mapper.ClauseMapper;
import com.aiid.aidoc.repository.mapper.ReviewIssueMapper;
import com.aiid.aidoc.repository.mapper.ReviewRecordMapper;
import com.aiid.aidoc.repository.mapper.StandardMapper;
import com.aiid.aidoc.service.ConflictReviewService;
import com.aiid.aidoc.service.support.ConflictReviewEngine;
import com.aiid.aidoc.service.support.RegulationTextParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ConflictReviewServiceImpl implements ConflictReviewService {
    private static final Set<String> REVIEW_TYPES = Set.of("superior", "internal", "replacement", "existing");
    private static final Set<String> ISSUE_STATUSES = Set.of("pending", "resolved", "ignored");
    private static final int MAX_CONTENT_LENGTH = 1_000_000;

    private final ReviewRecordMapper reviewRecordMapper;
    private final ReviewIssueMapper reviewIssueMapper;
    private final StandardMapper standardMapper;
    private final ClauseMapper clauseMapper;
    private final RegulationTextParser textParser;
    private final ConflictReviewEngine reviewEngine;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ReviewRecord create(String userId, ConflictReviewCreateRequest request) {
        String documentName = normalize(request.getDocumentName());
        String content = request.getContent() == null ? "" : request.getContent().trim();
        if (documentName.isBlank()) {
            throw new IllegalArgumentException("制度名称不能为空");
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("制度正文不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("制度正文不能超过 100 万字符");
        }
        String reviewType = normalize(request.getReviewType());
        if (!REVIEW_TYPES.contains(reviewType)) {
            throw new IllegalArgumentException("不支持的审查类型");
        }
        List<String> standardIds = distinct(request.getStandardIds());
        if (standardIds.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一份对照制度");
        }
        Set<String> replacedIds = new LinkedHashSet<>(distinct(request.getReplacedStandardIds()));
        if (!"replacement".equals(reviewType) && !replacedIds.isEmpty()) {
            throw new IllegalArgumentException("只有替换审查可以设置被替换制度");
        }
        LocalDate baselineDate = request.getBaselineDate() == null ? LocalDate.now() : request.getBaselineDate();
        List<RegulationTextParser.ParsedClause> sourceClauses = textParser.parse(content);
        if (sourceClauses.isEmpty()) {
            throw new IllegalArgumentException("未能从文件中解析出条款或段落");
        }

        List<Standard> selectedStandards = loadStandards(standardIds);
        if (selectedStandards.size() != standardIds.size()) {
            throw new IllegalArgumentException("部分对照制度不存在，请刷新制度库");
        }
        List<ConflictReviewEngine.ComparisonClause> comparisons = buildComparisons(
                selectedStandards, replacedIds, reviewType, baselineDate);
        if (comparisons.isEmpty()) {
            throw new IllegalArgumentException("所选制度在基准日期下没有可比较的有效条文");
        }

        ReviewRecord record = new ReviewRecord();
        record.setId(UUID.randomUUID().toString());
        record.setUserId(userId);
        record.setDocumentName(documentName);
        record.setContent(content);
        record.setDimensions(toJson(List.of("compliance", "consistency")));
        record.setStandardIds(toJson(standardIds));
        record.setReplacedStandardIds(toJson(new ArrayList<>(replacedIds)));
        record.setReviewType(reviewType);
        record.setBaselineDate(baselineDate);
        record.setClauseCount(sourceClauses.size());
        record.setEngineVersion(ConflictReviewEngine.ENGINE_VERSION);
        record.setStatus("running");
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());
        reviewRecordMapper.insert(record);

        ConflictReviewEngine.EngineResult result = reviewEngine.review(sourceClauses, comparisons);
        for (ConflictReviewEngine.ConflictFinding finding : result.findings()) {
            ReviewIssue issue = new ReviewIssue();
            issue.setId(UUID.randomUUID().toString());
            issue.setRecordId(record.getId());
            issue.setSeverity(finding.severity());
            issue.setDimension(finding.dimension());
            issue.setConflictType(finding.conflictType());
            issue.setReviewScope(finding.scope());
            issue.setTitle(finding.title());
            issue.setDescription(finding.description());
            issue.setOriginalSnippet(finding.source().content());
            issue.setSnippetStart(finding.source().sourceOffset());
            issue.setChapterRef(finding.source().clauseNumber());
            issue.setSourceClauseNumber(finding.source().clauseNumber());
            Standard standard = finding.target().standard();
            Clause clause = finding.target().clause();
            issue.setStandardId(standard.getId());
            issue.setClauseId(clause.getId());
            issue.setStandardRef(standard.getNumber() + " " + standard.getName());
            issue.setStandardClause(clause.getClauseNumber());
            issue.setStandardText(clause.getContent());
            issue.setSuggestionText(finding.suggestion());
            issue.setConfidence(finding.confidenceValue());
            issue.setStatus("pending");
            issue.setCreatedAt(LocalDateTime.now());
            reviewIssueMapper.insert(issue);
        }

        Map<String, Object> summary = new LinkedHashMap<>(result.summary());
        summary.put("comparedStandardCount", selectedStandards.size());
        summary.put("excludedReplacedCount", replacedIds.size());
        record.setSummary(toJson(summary));
        record.setStatus("completed");
        record.setUpdatedAt(LocalDateTime.now());
        reviewRecordMapper.updateById(record);
        return record;
    }

    private List<ConflictReviewEngine.ComparisonClause> buildComparisons(
            List<Standard> standards, Set<String> replacedIds, String reviewType, LocalDate baselineDate) {
        List<ConflictReviewEngine.ComparisonClause> comparisons = new ArrayList<>();
        for (Standard standard : standards) {
            boolean replaced = "replacement".equals(reviewType) && replacedIds.contains(standard.getId());
            if (!replaced && !isEffective(standard, baselineDate)) {
                continue;
            }
            String scope = replaced ? "old_version" : scopeOf(standard, reviewType);
            List<Clause> clauses = clauseMapper.selectList(new LambdaQueryWrapper<Clause>()
                    .eq(Clause::getStandardId, standard.getId())
                    .orderByAsc(Clause::getCreatedAt));
            for (Clause clause : clauses) {
                if (clause.getContent() != null && !clause.getContent().isBlank()) {
                    comparisons.add(new ConflictReviewEngine.ComparisonClause(standard, clause, scope));
                }
            }
        }
        return comparisons;
    }

    private boolean isEffective(Standard standard, LocalDate baselineDate) {
        if (!"active".equals(standard.getStatus())) {
            return false;
        }
        if (standard.getEffectiveDate() != null && baselineDate.isBefore(standard.getEffectiveDate())) {
            return false;
        }
        return standard.getExpiryDate() == null || !baselineDate.isAfter(standard.getExpiryDate());
    }

    private String scopeOf(Standard standard, String reviewType) {
        if ("replacement".equals(reviewType)) {
            return "remaining_internal";
        }
        if ("superior".equals(reviewType) || "superior".equals(standard.getCategory())) {
            return "superior";
        }
        return "internal";
    }

    private List<Standard> loadStandards(List<String> ids) {
        List<Standard> standards = new ArrayList<>();
        for (String id : ids) {
            Standard standard = standardMapper.selectById(id);
            if (standard != null) {
                standards.add(standard);
            }
        }
        return standards;
    }

    @Override
    public ReviewRecord getOwnedRecord(String id, String userId) {
        ReviewRecord record = reviewRecordMapper.selectById(id);
        if (record == null || userId == null || !userId.equals(record.getUserId())) {
            return null;
        }
        return record;
    }

    @Override
    public List<ReviewIssue> getIssues(String recordId) {
        return reviewIssueMapper.selectList(new LambdaQueryWrapper<ReviewIssue>()
                .eq(ReviewIssue::getRecordId, recordId)
                .orderByAsc(ReviewIssue::getCreatedAt));
    }

    @Override
    public Page<ReviewRecord> list(String userId, long page, long pageSize) {
        return reviewRecordMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ReviewRecord>()
                        .eq(ReviewRecord::getUserId, userId)
                        .orderByDesc(ReviewRecord::getCreatedAt));
    }

    @Override
    public ReviewIssue decide(String recordId, String issueId, String userId, String status, String comment) {
        if (!ISSUE_STATUSES.contains(status)) {
            throw new IllegalArgumentException("无效的复核状态");
        }
        ReviewRecord record = getOwnedRecord(recordId, userId);
        if (record == null) {
            return null;
        }
        ReviewIssue issue = reviewIssueMapper.selectById(issueId);
        if (issue == null || !recordId.equals(issue.getRecordId())) {
            return null;
        }
        issue.setStatus(status);
        issue.setReviewComment(normalize(comment));
        reviewIssueMapper.updateById(issue);
        return issue;
    }

    @Override
    @Transactional
    public boolean delete(String id, String userId) {
        ReviewRecord record = getOwnedRecord(id, userId);
        if (record == null) {
            return false;
        }
        reviewIssueMapper.delete(new LambdaQueryWrapper<ReviewIssue>().eq(ReviewIssue::getRecordId, id));
        reviewRecordMapper.deleteById(id);
        return true;
    }

    private List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(this::normalize).filter(value -> !value.isBlank()).distinct().toList();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审查数据序列化失败", e);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
