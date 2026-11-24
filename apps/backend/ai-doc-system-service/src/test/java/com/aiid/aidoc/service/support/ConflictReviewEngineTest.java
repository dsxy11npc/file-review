package com.aiid.aidoc.service.support;

import com.aiid.aidoc.model.entity.Clause;
import com.aiid.aidoc.model.entity.Standard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictReviewEngineTest {
    private final RegulationTextParser parser = new RegulationTextParser();
    private final ConflictReviewEngine engine = new ConflictReviewEngine();

    @Test
    void detectsDifferentLeaveLimitsWithEvidence() {
        Standard standard = standard("上级请假制度");
        Clause clause = clause("上级请假制度", "员工请假超过2天应由分管领导审批。");

        ConflictReviewEngine.EngineResult result = engine.review(
                parser.parse("第一条 员工请假超过3天由部门负责人审批。"),
                List.of(new ConflictReviewEngine.ComparisonClause(standard, clause, "superior"))
        );

        assertTrue(result.findings().stream().anyMatch(f -> f.conflictType().equals("numeric_standard")));
        assertTrue(result.findings().stream().anyMatch(f -> f.conflictType().equals("approval_authority")));
        assertEquals("第一条", result.findings().get(0).source().clauseNumber());
        assertEquals(clause.getId(), result.findings().get(0).target().clause().getId());
    }

    @Test
    void treatsNaturalDaysAndDaysAsTheSameUnit() {
        Standard standard = standard("请假制度");
        Clause clause = clause("请假制度", "员工请假不得超过3个自然日。");

        ConflictReviewEngine.EngineResult result = engine.review(
                parser.parse("第一条 员工请假不得超过3天。"),
                List.of(new ConflictReviewEngine.ComparisonClause(standard, clause, "internal"))
        );

        assertTrue(result.findings().stream().noneMatch(f -> f.conflictType().equals("numeric_standard")));
    }

    @Test
    void doesNotCompareUnrelatedNumericRequirements() {
        Standard standard = standard("采购制度");
        Clause clause = clause("采购制度", "采购金额超过5万元应由总经理审批。");

        ConflictReviewEngine.EngineResult result = engine.review(
                parser.parse("第一条 员工请假不得超过3天。"),
                List.of(new ConflictReviewEngine.ComparisonClause(standard, clause, "internal"))
        );

        assertTrue(result.findings().isEmpty());
    }

    @Test
    void detectsChineseNumbersAndFullWidthDigits() {
        Standard standard = standard("上级请假制度");
        Clause clause = clause("上级请假制度", "员工请假不得超过二十个工作日。");

        ConflictReviewEngine.EngineResult result = engine.review(
                parser.parse("第一条 员工请假不得超过３个工作日。"),
                List.of(new ConflictReviewEngine.ComparisonClause(standard, clause, "superior"))
        );

        assertTrue(result.findings().stream().anyMatch(f -> f.conflictType().equals("numeric_standard")));
    }

    @Test
    void comparesAmountsAcrossYuanAndTenThousandYuan() {
        Standard standard = standard("采购制度");
        Clause clause = clause("采购制度", "采购金额超过1万元应履行集体审批程序。");

        ConflictReviewEngine.EngineResult result = engine.review(
                parser.parse("第一条 采购金额超过5000元应履行集体审批程序。"),
                List.of(new ConflictReviewEngine.ComparisonClause(standard, clause, "internal"))
        );

        assertTrue(result.findings().stream().anyMatch(f -> f.conflictType().equals("numeric_standard")));
    }

    @Test
    void ignoresCalendarYearsInsteadOfTreatingThemAsDurations() {
        Standard standard = standard("档案制度");
        Clause clause = clause("档案制度", "档案保存期限为3年。");

        ConflictReviewEngine.EngineResult result = engine.review(
                parser.parse("第一条 二〇二六年形成的档案应单独编目。"),
                List.of(new ConflictReviewEngine.ComparisonClause(standard, clause, "internal"))
        );

        assertTrue(result.findings().stream().noneMatch(f -> f.conflictType().equals("numeric_standard")));
    }

    private static Standard standard(String name) {
        Standard standard = new Standard();
        standard.setId("standard-1");
        standard.setNumber("TEST-001");
        standard.setName(name);
        return standard;
    }

    private static Clause clause(String title, String content) {
        Clause clause = new Clause();
        clause.setId("clause-1");
        clause.setStandardId("standard-1");
        clause.setClauseNumber("第二条");
        clause.setTitle(title);
        clause.setContent(content);
        return clause;
    }
}
