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
import com.aiid.aidoc.service.support.ConflictReviewEngine;
import com.aiid.aidoc.service.support.RegulationTextParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConflictReviewServiceImplTest {
    @Mock
    private ReviewRecordMapper recordMapper;
    @Mock
    private ReviewIssueMapper issueMapper;
    @Mock
    private StandardMapper standardMapper;
    @Mock
    private ClauseMapper clauseMapper;

    @Test
    void createsCompletedReviewAndPersistsTraceableIssues() {
        Standard superior = standard("superior-1", "上级请假制度", "superior", "active");
        when(standardMapper.selectById("superior-1")).thenReturn(superior);
        when(clauseMapper.selectList(any())).thenReturn(List.of(
                clause("superior-1", "员工请假超过2天，应由分管领导审批。")));
        ConflictReviewServiceImpl service = service();

        ReviewRecord result = service.create("user-1", request("superior", "superior-1"));

        assertEquals("completed", result.getStatus());
        assertEquals(1, result.getClauseCount());
        ArgumentCaptor<ReviewIssue> issueCaptor = ArgumentCaptor.forClass(ReviewIssue.class);
        verify(issueMapper, atLeastOnce()).insert(issueCaptor.capture());
        ReviewIssue issue = issueCaptor.getValue();
        assertEquals("superior-1", issue.getStandardId());
        assertEquals("第一条", issue.getSourceClauseNumber());
        assertTrue(issue.getStandardText().contains("分管领导"));
    }

    @Test
    void replacementKeepsOldVersionComparisonButExcludesItFromCurrentScope() {
        Standard old = standard("old-1", "旧请假制度", "internal", "superseded");
        when(standardMapper.selectById("old-1")).thenReturn(old);
        when(clauseMapper.selectList(any())).thenReturn(List.of(
                clause("old-1", "员工请假超过3天，由部门负责人审批。")));
        ConflictReviewServiceImpl service = service();
        ConflictReviewCreateRequest request = request("replacement", "old-1");
        request.setReplacedStandardIds(List.of("old-1"));

        ReviewRecord result = service.create("user-1", request);

        ArgumentCaptor<ReviewIssue> issueCaptor = ArgumentCaptor.forClass(ReviewIssue.class);
        verify(issueMapper, atLeastOnce()).insert(issueCaptor.capture());
        assertEquals("old_version", issueCaptor.getValue().getReviewScope());
        assertTrue(result.getSummary().contains("\"excludedReplacedCount\":1"));
    }

    private ConflictReviewServiceImpl service() {
        return new ConflictReviewServiceImpl(recordMapper, issueMapper, standardMapper, clauseMapper,
                new RegulationTextParser(), new ConflictReviewEngine(),
                new ObjectMapper().findAndRegisterModules());
    }

    private static ConflictReviewCreateRequest request(String type, String standardId) {
        ConflictReviewCreateRequest request = new ConflictReviewCreateRequest();
        request.setDocumentName("新请假制度.md");
        request.setContent("第一条 员工请假超过4天，由总经理审批。");
        request.setReviewType(type);
        request.setBaselineDate(LocalDate.of(2026, 11, 22));
        request.setStandardIds(List.of(standardId));
        request.setReplacedStandardIds(List.of());
        return request;
    }

    private static Standard standard(String id, String name, String category, String status) {
        Standard standard = new Standard();
        standard.setId(id);
        standard.setNumber("TEST-001");
        standard.setName(name);
        standard.setCategory(category);
        standard.setStatus(status);
        standard.setEffectiveDate(LocalDate.of(2020, 1, 1));
        return standard;
    }

    private static Clause clause(String standardId, String content) {
        Clause clause = new Clause();
        clause.setId(standardId + "-clause-1");
        clause.setStandardId(standardId);
        clause.setClauseNumber("第二条");
        clause.setContent(content);
        return clause;
    }
}
