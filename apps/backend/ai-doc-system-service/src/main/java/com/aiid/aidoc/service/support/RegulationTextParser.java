package com.aiid.aidoc.service.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RegulationTextParser {
    private static final Pattern CLAUSE_HEADING = Pattern.compile(
            "(?m)^\\s*(第[零〇一二三四五六七八九十百千万两0-9]+条)(?:[、.．\\s　]*)");

    public List<ParsedClause> parse(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        Matcher matcher = CLAUSE_HEADING.matcher(normalized);
        List<ClausePosition> positions = new ArrayList<>();
        while (matcher.find()) {
            positions.add(new ClausePosition(matcher.start(), matcher.end(), matcher.group(1)));
        }

        List<ParsedClause> clauses = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            ClausePosition current = positions.get(i);
            int end = i + 1 < positions.size() ? positions.get(i + 1).start() : normalized.length();
            String body = normalized.substring(current.contentStart(), end).trim();
            if (!body.isBlank()) {
                clauses.add(new ParsedClause(current.heading(), body, current.start()));
            }
        }
        if (!clauses.isEmpty()) {
            return clauses;
        }

        String[] paragraphs = normalized.split("\\n\\s*\\n|\\n");
        int offset = 0;
        int paragraphNumber = 1;
        for (String paragraph : paragraphs) {
            String body = paragraph.trim();
            int start = normalized.indexOf(paragraph, offset);
            offset = Math.max(offset, start + paragraph.length());
            if (!body.isBlank() && !body.matches("^#{1,6}\\s+.*")) {
                clauses.add(new ParsedClause("段落" + paragraphNumber++, body, Math.max(start, 0)));
            }
        }
        return clauses;
    }

    private record ClausePosition(int start, int contentStart, String heading) {
    }

    public record ParsedClause(String clauseNumber, String content, int sourceOffset) {
    }
}
