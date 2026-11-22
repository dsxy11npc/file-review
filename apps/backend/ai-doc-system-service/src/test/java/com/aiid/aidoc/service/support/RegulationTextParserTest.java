package com.aiid.aidoc.service.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegulationTextParserTest {
    private final RegulationTextParser parser = new RegulationTextParser();

    @Test
    void parsesChineseClauseHeadingsAndKeepsBody() {
        List<RegulationTextParser.ParsedClause> clauses = parser.parse(
                "员工请假管理办法\n第一条 请假一天由部门负责人审批。\n\n第二条 请假三天由分管领导批准。"
        );

        assertEquals(2, clauses.size());
        assertEquals("第一条", clauses.get(0).clauseNumber());
        assertEquals("请假一天由部门负责人审批。", clauses.get(0).content());
        assertEquals("第二条", clauses.get(1).clauseNumber());
    }

    @Test
    void fallsBackToNonEmptyParagraphs() {
        List<RegulationTextParser.ParsedClause> clauses = parser.parse("# 制度标题\n\n第一段内容\n第二段内容");

        assertEquals(2, clauses.size());
        assertEquals("段落1", clauses.get(0).clauseNumber());
        assertEquals("第一段内容", clauses.get(0).content());
    }
}
