package com.aiid.aidoc.service.support;

import com.aiid.aidoc.model.entity.Clause;
import com.aiid.aidoc.model.entity.Standard;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
    public static final String ENGINE_VERSION = "local-rules-v2";

    private static final Pattern NUMBER_REQUIREMENT = Pattern.compile(
            "([0-9０-９]+(?:\\.[0-9０-９]+)?|[零〇一二三四五六七八九十百千万两]+)\\s*"
                    + "(个?工作日|自然日|日|天|小时|万元|元|%|％|个月|月|年)");
    private static final Pattern APPROVAL_ROLE = Pattern.compile(
            "(?:由|经|报)([\\u4e00-\\u9fa5]{2,20}?)(?:审批|批准|审核)");
    private static final List<String> TOPICS = List.of(
            "请假", "休假", "报销", "采购", "加班", "出差", "会议", "保密", "合同",
            "验收", "付款", "培训", "档案", "资产", "用章", "值班", "招聘", "离职",
            "考勤", "工资", "借款", "印章", "公文", "消防", "招投标", "差旅"
    );
    private static final Map<Character, Integer> CHINESE_DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('〇', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('两', 2), Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5),
            Map.entry('六', 6), Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9)
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
        List<NumericRequirement> sourceValues = normalizeNumbers(source.content());
        List<NumericRequirement> targetValues = normalizeNumbers(target.clause().getContent());
        for (NumericRequirement sourceValue : sourceValues) {
            for (NumericRequirement targetValue : targetValues) {
                if (!sourceValue.unit().equals(targetValue.unit())
                        || sourceValue.value().compareTo(targetValue.value()) == 0) {
                    continue;
                }
                String key = source.clauseNumber() + "|" + target.clause().getId() + "|number|"
                        + sourceValue.unit() + "|" + sourceValue.value() + "|" + targetValue.value();
                if (!deduplication.add(key)) {
                    continue;
                }
                findings.add(new ConflictFinding(
                        "warning", "consistency", "numeric_standard", target.scope(),
                        "同类事项的数量标准不一致",
                        "两条规定涉及相同或相近事项，但" + unitLabel(sourceValue.unit()) + "标准分别为 "
                                + sourceValue.display() + " 和 " + targetValue.display()
                                + "。差异可能源于适用范围、例外条件或合理加严，请人工确认。",
                        source, target, 0.82,
                        "核对两条规定的适用对象和条件；如确属同一事项，建议统一标准或明确例外范围。"
                ));
            }
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
                "warning", "compliance", "approval_authority", target.scope(),
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

    private List<NumericRequirement> normalizeNumbers(String text) {
        List<NumericRequirement> values = new ArrayList<>();
        if (text == null) {
            return values;
        }
        Matcher matcher = NUMBER_REQUIREMENT.matcher(text);
        while (matcher.find()) {
            String token = normalizeFullWidthDigits(matcher.group(1));
            String rawUnit = matcher.group(2);
            if ("年".equals(rawUnit) && isYear(token)) {
                continue;
            }
            BigDecimal value = parseNumber(token);
            String unit = switch (rawUnit) {
                case "日", "自然日" -> "天";
                case "个工作日", "工作日" -> "工作日";
                case "万元" -> "元";
                case "个月" -> "月";
                case "％" -> "%";
                default -> rawUnit;
            };
            if ("万元".equals(rawUnit)) {
                value = value.multiply(BigDecimal.valueOf(10_000));
            }
            values.add(new NumericRequirement(value, unit, token + rawUnit));
        }
        return values;
    }

    private String normalizeFullWidthDigits(String token) {
        StringBuilder result = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            result.append(current >= '０' && current <= '９' ? (char) ('0' + current - '０') : current);
        }
        return result.toString();
    }

    private BigDecimal parseNumber(String token) {
        if (token.matches("\\d+(?:\\.\\d+)?")) {
            return new BigDecimal(token);
        }
        long total = 0;
        long section = 0;
        int number = 0;
        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            Integer digit = CHINESE_DIGITS.get(current);
            if (digit != null) {
                number = digit;
                continue;
            }
            int unit = switch (current) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10_000;
                default -> throw new IllegalArgumentException("不支持的中文数字: " + token);
            };
            if (unit == 10_000) {
                total += (section + number) * unit;
                section = 0;
            } else {
                section += (number == 0 ? 1 : number) * (long) unit;
            }
            number = 0;
        }
        return BigDecimal.valueOf(total + section + number);
    }

    private boolean isYear(String token) {
        if (token.matches("\\d{4}")) {
            int year = Integer.parseInt(token);
            return year >= 1900 && year <= 2999;
        }
        return token.length() == 4 && token.chars().allMatch(c -> CHINESE_DIGITS.containsKey((char) c));
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
            case "天", "工作日", "小时", "月", "年" -> "时限";
            case "元" -> "金额";
            case "%" -> "比例";
            default -> "数量";
        };
    }

    private record NumericRequirement(BigDecimal value, String unit, String display) {
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
