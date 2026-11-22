package com.aiid.aidoc.service.support;

import com.aiid.aidoc.model.entity.Clause;
import com.aiid.aidoc.model.entity.Standard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConflictReviewEngine {
    public static final String ENGINE_VERSION = "local-rules-v1";

    private static final Pattern NUMBER_REQUIREMENT = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(个?工作日|自然日|日|天|小时|万元|元|%|％|个月|月|年)");
    private static final Pattern APPROVAL_ROLE = Pattern.compile(
            "(?:由|经|报)([\\u4e00-\\u9fa5]{2,20}?)(?:审批|批准|审核)");
    private static final List<String> TOPICS = List.of(
            "请假", "休假", "审批", "报销", "采购", "加班", "出差", "会议", "保密",
            "合同", "验收", "付款", "培训", "安全", "档案", "资产", "用章", "值班",
            "招聘", "离职", "考勤", "工资", "借款", "印章", "公文", "财务", "人事"
    );

    public EngineResult review(List<RegulationTextParser.ParsedClause> sourceClauses,
                               List<ComparisonClause> comparisonClauses) {
        List<ConflictFinding> findings = new ArrayList<>();
        Set<String> deduplication = new HashSet<>();

        for (RegulationTextParser.ParsedClause source : sourceClauses) {
            for (ComparisonClause target : comparisonClauses) {
                if (!isRelated(source.content(), target.clause().getContent())) {
                    continue;
                }
                compareNumbers(source, target, findings, deduplication);
                compareApprovalAuthority(source, target, findings, deduplication);
            }
        }

        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        bySeverity.put("critical", 0);
        bySeverity.put("warning", 0);
        bySeverity.put("suggestion", 0);
        for (ConflictFinding finding : findings) {
            bySeverity.compute(finding.severity(), (key, count) -> count == null ? 1 : count + 1);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("bySeverity", bySeverity);
        summary.put("total", findings.size());
        summary.put("clauseCount", sourceClauses.size());
        summary.put("engineVersion", ENGINE_VERSION);
        return new EngineResult(findings, summary);
    }

    private void compareNumbers(RegulationTextParser.ParsedClause source,
                                ComparisonClause target,
                                List<ConflictFinding> findings,
                                Set<String> deduplication) {
        Map<String, BigDecimal> sourceValues = normalizeNumbers(source.content());
        Map<String, BigDecimal> targetValues = normalizeNumbers(target.clause().getContent());
        if (sourceValues.isEmpty() || targetValues.isEmpty()) {
            return;
        }
        for (Map.Entry<String, BigDecimal> sourceValue : sourceValues.entrySet()) {
            BigDecimal targetValue = targetValues.get(sourceValue.getKey());
            if (targetValue == null || targetValue.compareTo(sourceValue.getValue()) == 0) {
                continue;
            }
            String key = source.clauseNumber() + "|" + target.clause().getId() + "|number|"
                    + sourceValue.getKey() + "|" + targetValue;
            if (!deduplication.add(key)) {
                continue;
            }
            findings.add(new ConflictFinding(
                    "warning", "consistency", "numeric_standard", target.scope(),
                    "同类事项的数量标准不一致",
                    "两条规定涉及相同或相近事项，但" + unitLabel(sourceValue.getKey()) + "标准分别为 "
                            + sourceValue.getValue().stripTrailingZeros().toPlainString() + " 和 "
                            + targetValue.stripTrailingZeros().toPlainString()
                            + "。差异可能源于适用范围、例外条件或合理加严，请人工确认。",
                    source, target, 0.82,
                    "核对两条规定的适用对象和条件；如确属同一事项，建议统一标准或明确例外范围。"
            ));
        }
    }

    private void compareApprovalAuthority(RegulationTextParser.ParsedClause source,
                                          ComparisonClause target,
                                          List<ConflictFinding> findings,
                                          Set<String> deduplication) {
        Set<String> sourceRoles = extractApprovalRoles(source.content());
        Set<String> targetRoles = extractApprovalRoles(target.clause().getContent());
        if (sourceRoles.isEmpty() || targetRoles.isEmpty() || sourceRoles.equals(targetRoles)) {
            return;
        }
        String key = source.clauseNumber() + "|" + target.clause().getId() + "|approval";
        if (!deduplication.add(key)) {
            return;
        }
        findings.add(new ConflictFinding(
                "critical", "compliance", "approval_authority", target.scope(),
                "同一事项的审批主体可能不一致",
                "待审条款中的审批主体为“" + String.join("、", sourceRoles)
                        + "”，对照条款中的审批主体为“" + String.join("、", targetRoles)
                        + "”。需确认是否存在分级审批或不同适用条件。",
                source, target, 0.86,
                "核对审批层级和适用范围；如针对同一事项，建议统一审批主体或明确各级审批边界。"
        ));
    }

    private boolean isRelated(String source, String target) {
        Set<String> sourceTopics = extractTopics(source);
        Set<String> targetTopics = extractTopics(target);
        sourceTopics.retainAll(targetTopics);
        if (!sourceTopics.isEmpty()) {
            return true;
        }
        Set<String> sourceBigrams = chineseBigrams(source);
        Set<String> targetBigrams = chineseBigrams(target);
        Set<String> intersection = new HashSet<>(sourceBigrams);
        intersection.retainAll(targetBigrams);
        if (intersection.size() < 3) {
            return false;
        }
        Set<String> union = new HashSet<>(sourceBigrams);
        union.addAll(targetBigrams);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= 0.08;
    }

    private Set<String> extractTopics(String text) {
        Set<String> topics = new HashSet<>();
        for (String topic : TOPICS) {
            if (text != null && text.contains(topic)) {
                topics.add(topic);
            }
        }
        return topics;
    }

    private Set<String> chineseBigrams(String text) {
        String chinese = text == null ? "" : text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < chinese.length(); i++) {
            result.add(chinese.substring(i, i + 2));
        }
        return result;
    }

    private Map<String, BigDecimal> normalizeNumbers(String text) {
        Map<String, BigDecimal> values = new HashMap<>();
        if (text == null) {
            return values;
        }
        Matcher matcher = NUMBER_REQUIREMENT.matcher(text);
        while (matcher.find()) {
            String rawUnit = matcher.group(2);
            String unit = switch (rawUnit) {
                case "日", "自然日" -> "天";
                case "个工作日", "工作日" -> "工作日";
                case "％" -> "%";
                default -> rawUnit;
            };
            values.putIfAbsent(unit, new BigDecimal(matcher.group(1)));
        }
        return values;
    }

    private Set<String> extractApprovalRoles(String text) {
        Set<String> roles = new LinkedHashSet<>();
        if (text == null) {
            return roles;
        }
        Matcher matcher = APPROVAL_ROLE.matcher(text);
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }

    private String unitLabel(String unit) {
        return switch (unit) {
            case "天", "工作日" -> "时限";
            case "元", "万元" -> "金额";
            case "%" -> "比例";
            default -> "数量";
        };
    }

    public record ComparisonClause(Standard standard, Clause clause, String scope) {
    }

    public record ConflictFinding(
            String severity,
            String dimension,
            String conflictType,
            String scope,
            String title,
            String description,
            RegulationTextParser.ParsedClause source,
            ComparisonClause target,
            double confidence,
            String suggestion
    ) {
        public BigDecimal confidenceValue() {
            return BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record EngineResult(List<ConflictFinding> findings, Map<String, Object> summary) {
    }
}
