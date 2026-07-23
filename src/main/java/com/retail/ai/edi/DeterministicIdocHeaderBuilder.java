package com.retail.ai.edi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DeterministicIdocHeaderBuilder {

    public HeaderBuildResult buildHeaderFragments(List<EdiSegment> segments) {
        List<String> fragments = new ArrayList<>();
        Set<Integer> handledSequenceIndexes = new HashSet<>();

        String docNum = null;
        String sender = null;
        String receiver = null;
        String currency = null;
        String purchaseOrder = null;
        String creationDate = null;
        String creationTime = null;

        for (EdiSegment segment : segments) {
            if (segment == null || segment.getName() == null) {
                continue;
            }
            String name = segment.getName().toUpperCase();
            List<String> fields = segment.getFields();
            switch (name) {
                case "UNB" -> {
                    if (fields.size() > 1 && !fields.get(1).isBlank()) {
                        String[] senderTokens = splitField(fields.get(1));
                        sender = senderTokens.length > 0 ? senderTokens[0] : fields.get(1).trim();
                    }
                    if (fields.size() > 2 && !fields.get(2).isBlank()) {
                        String[] receiverTokens = splitField(fields.get(2));
                        receiver = receiverTokens.length > 0 ? receiverTokens[0] : fields.get(2).trim();
                    }
                    if (sender != null || receiver != null) {
                        handledSequenceIndexes.add(segment.getSequenceIndex());
                    }
                }
                case "BGM" -> {
                    if (fields.size() > 1 && !fields.get(1).isBlank()) {
                        purchaseOrder = fields.get(1).trim();
                        if (docNum == null) {
                            String candidate = extractAfterQualifier(fields.get(0));
                            if (!isBgmDocumentTypeCode(candidate)) {
                                docNum = candidate;
                            }
                        }
                    } else if (!fields.isEmpty() && !fields.get(0).isBlank()) {
                        String candidate = fields.get(0).trim();
                        if (fields.size() == 1 || !isBgmDocumentTypeCode(candidate)) {
                            purchaseOrder = candidate;
                        }
                        if (docNum == null) {
                            docNum = candidate;
                        }
                    }
                    if (purchaseOrder != null || docNum != null) {
                        handledSequenceIndexes.add(segment.getSequenceIndex());
                    }
                }
                case "RFF" -> {
                    if (!fields.isEmpty()) {
                        String[] parts = splitField(fields.get(0));
                        if (parts.length > 1 && "ON".equalsIgnoreCase(parts[0]) && !parts[1].isBlank()) {
                            purchaseOrder = parts[1].trim();
                            if (docNum == null) {
                                docNum = purchaseOrder;
                            }
                            handledSequenceIndexes.add(segment.getSequenceIndex());
                        }
                    }
                }
                case "CUX" -> {
                    if (!fields.isEmpty()) {
                        String[] parts = splitField(fields.get(0));
                        String value = parts.length > 1 ? parts[1] : parts[0];
                        if (!value.isBlank()) {
                            currency = value.trim();
                            handledSequenceIndexes.add(segment.getSequenceIndex());
                        }
                    }
                }
                case "DTM" -> {
                    if (!fields.isEmpty()) {
                        String[] parts = splitField(fields.get(0));
                        String qualifier = parts.length > 0 ? parts[0] : "";
                        String value = parts.length > 1 ? parts[1] : "";
                        if ("137".equals(qualifier) || "102".equals(qualifier) || "131".equals(qualifier)) {
                            creationDate = value.trim();
                            if (parts.length > 2 && isPossibleTime(parts[2])) {
                                creationTime = parts[2].trim();
                            }
                            handledSequenceIndexes.add(segment.getSequenceIndex());
                        }
                    }
                }
                case "UNH" -> {
                    if (!fields.isEmpty() && !fields.get(0).isBlank()) {
                        docNum = fields.get(0).trim();
                        handledSequenceIndexes.add(segment.getSequenceIndex());
                    }
                }
            }
        }

        if (docNum != null || sender != null || receiver != null || creationDate != null || creationTime != null) {
            fragments.add(buildEdiDc40(padDocNum(docNum), sender, receiver, creationDate, creationTime));
        }
        if (docNum != null || purchaseOrder != null || currency != null || creationDate != null) {
            fragments.add(buildE1Edk01(padDocNum(docNum), purchaseOrder, currency, creationDate, receiver));
        }

        return new HeaderBuildResult(fragments, handledSequenceIndexes);
    }

    private String buildEdiDc40(String docNum, String sender, String receiver, String creationDate, String creationTime) {
        StringBuilder xml = new StringBuilder();
        xml.append("<EDI_DC40 SEGMENT=\"1\">");
        xml.append("<TABNAM>EDI_DC40</TABNAM>");
        xml.append("<MANDT>100</MANDT>");
        xml.append("<DOCNUM>").append(escapeXml(docNum)).append("</DOCNUM>");
        xml.append("<DOCREL>750</DOCREL>");
        xml.append("<STATUS>30</STATUS>");
        xml.append("<DIRECT>2</DIRECT>");
        xml.append("<OUTMOD>2</OUTMOD>");
        xml.append("<IDOCTYP>ORDERS05</IDOCTYP>");
        xml.append("<MESTYP>ORDERS</MESTYP>");
        xml.append("<SNDPRN>").append(escapeXml(sender)).append("</SNDPRN>");
        xml.append("<SNDPRT>LS</SNDPRT>");
        xml.append("<RCVPRN>").append(escapeXml(receiver)).append("</RCVPRN>");
        xml.append("<RCVPRT>LS</RCVPRT>");
        if (creationDate != null && !creationDate.isBlank()) {
            xml.append("<CREDAT>").append(escapeXml(creationDate)).append("</CREDAT>");
        }
        if (creationTime != null && !creationTime.isBlank()) {
            xml.append("<CRETIM>").append(escapeXml(creationTime)).append("</CRETIM>");
        }
        xml.append("</EDI_DC40>");
        return xml.toString();
    }

    private String buildE1Edk01(String docNum, String purchaseOrder, String currency, String creationDate, String receiver) {
        StringBuilder xml = new StringBuilder();
        xml.append("<E1EDK01 SEGMENT=\"1\">");
        xml.append("<ACTION>000</ACTION>");
        xml.append("<KZABS>X</KZABS>");
        if (currency != null && !currency.isBlank()) {
            xml.append("<CURCY>").append(escapeXml(currency)).append("</CURCY>");
            xml.append("<HWAER>").append(escapeXml(currency)).append("</HWAER>");
        }
        xml.append("<WKURS>1.00000</WKURS>");
        xml.append("<BELNR>").append(escapeXml(docNum)).append("</BELNR>");
        if (creationDate != null && !creationDate.isBlank()) {
            xml.append("<DATUM>").append(escapeXml(creationDate)).append("</DATUM>");
            xml.append("<AEDAT>").append(escapeXml(creationDate)).append("</AEDAT>");
        }
        if (receiver != null && !receiver.isBlank()) {
            xml.append("<RECIP>").append(escapeXml(receiver)).append("</RECIP>");
        }
        if (purchaseOrder != null && !purchaseOrder.isBlank()) {
            xml.append("<BSTKD>").append(escapeXml(purchaseOrder)).append("</BSTKD>");
        }
        xml.append("</E1EDK01>");
        return xml.toString();
    }

    private String[] splitField(String field) {
        if (field == null) {
            return new String[]{"", ""};
        }
        String trimmed = field.trim();
        return trimmed.split(":", -1);
    }

    private String extractAfterQualifier(String field) {
        if (field == null) {
            return "";
        }
        String[] parts = splitField(field);
        if (parts.length <= 1) {
            return field.trim();
        }
        return parts[1].trim();
    }

    private boolean isBgmDocumentTypeCode(String value) {
        return value != null && value.matches("\\d{3}");
    }

    private boolean isPossibleTime(String value) {
        return value != null && value.matches("\\d{2,6}");
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String padDocNum(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!trimmed.matches("\\d+")) {
            return trimmed;
        }
        if (trimmed.length() >= 16) {
            return trimmed;
        }
        return "0".repeat(16 - trimmed.length()) + trimmed;
    }

    public static class HeaderBuildResult {
        private final List<String> fragments;
        private final Set<Integer> handledSegmentIndices;

        public HeaderBuildResult(List<String> fragments, Set<Integer> handledSegmentIndices) {
            this.fragments = fragments;
            this.handledSegmentIndices = handledSegmentIndices;
        }

        public List<String> getFragments() {
            return fragments;
        }

        public Set<Integer> getHandledSegmentIndices() {
            return handledSegmentIndices;
        }
    }
}
