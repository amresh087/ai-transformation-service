package com.retail.ai.edi;

import java.util.Set;

public class SegmentHierarchyRuleResolver {

    private final String tenant;
    private final String transactionTypeCode;
    private final String ruleSet;

    public SegmentHierarchyRuleResolver(String ruleSet, String tenant, String transactionTypeCode) {
        this.ruleSet = ruleSet;
        this.tenant = tenant;
        this.transactionTypeCode = transactionTypeCode;
    }

    public SegmentHierarchyRole roleFor(String segmentName, String rawSegment) {
        if (segmentName == null) {
            return SegmentHierarchyRole.IGNORE;
        }

        String upper = segmentName.toUpperCase();
        if ("MOA".equals(upper)) {
            String qualifier = extractQualifier(rawSegment);
            if ("39".equals(qualifier)) {
                return SegmentHierarchyRole.TRAILING;
            }
            return SegmentHierarchyRole.BELONGS_TO_LINE_ITEM;
        }

        Set<String> headerSegments = Set.of("UNB", "UNH", "BGM", "DTM", "NAD", "RFF", "CUX", "PAT");
        if (headerSegments.contains(upper)) {
            return SegmentHierarchyRole.HEADER;
        }

        if ("LIN".equals(upper)) {
            return SegmentHierarchyRole.STARTS_LINE_ITEM;
        }

        if (Set.of("PIA", "IMD").contains(upper)) {
            return SegmentHierarchyRole.BELONGS_TO_LINE_ITEM;
        }

        if (Set.of("QTY", "PRI").contains(upper)) {
            return SegmentHierarchyRole.BELONGS_TO_LINE_ITEM;
        }

        if (Set.of("UNT", "UNZ").contains(upper)) {
            return SegmentHierarchyRole.TRAILING;
        }

        return SegmentHierarchyRole.IGNORE;
    }

    private String extractQualifier(String rawSegment) {
        if (rawSegment == null) {
            return "";
        }
        int start = rawSegment.indexOf("<field>");
        if (start < 0) {
            return "";
        }
        int end = rawSegment.indexOf("</field>", start);
        if (end < 0) {
            return "";
        }
        String field = rawSegment.substring(start + 7, end).trim();
        int colon = field.indexOf(':');
        return colon >= 0 ? field.substring(0, colon) : field;
    }
}
