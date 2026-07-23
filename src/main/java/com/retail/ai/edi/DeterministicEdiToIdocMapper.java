package com.retail.ai.edi;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeterministicEdiToIdocMapper {

    public String map(EdiSegment segment) {
        if (segment == null || segment.getName() == null) {
            return null;
        }

        String name = segment.getName().toUpperCase();
        List<String> fields = segment.getFields();
        switch (name) {
            case "LIN":
                return mapLin(fields);
            case "QTY":
                return mapQty(fields);
            case "PRI":
                return mapPri(fields);
            case "PIA":
                return mapPia(fields);
            default:
                return null;
        }
    }

    private String mapLin(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String id = extractAfterQualifier(fields.get(0));
        if (id.isEmpty()) {
            return null;
        }
        return "<E1EDP19><MATNR>" + escapeXml(id) + "</MATNR></E1EDP19>";
    }

    private String mapQty(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String[] parts = splitQualifier(fields.get(0));
        String quantity = parts.length > 1 ? parts[1] : parts[0];
        String unit = parts.length > 2 ? parts[2] : "";
        if (quantity.isEmpty()) {
            return null;
        }
        StringBuilder xml = new StringBuilder();
        xml.append("<E1EDP20><MENGE>").append(escapeXml(quantity)).append("</MENGE>");
        if (!unit.isBlank()) {
            xml.append("<MENEE>").append(escapeXml(unit)).append("</MENEE>");
        }
        xml.append("</E1EDP20>");
        return xml.toString();
    }

    private String mapPri(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String[] parts = splitQualifier(fields.get(0));
        String amount = parts.length > 1 ? parts[1] : parts[0];
        if (amount.isEmpty()) {
            return null;
        }
        return "<E1EDP24><NETWR>" + escapeXml(amount) + "</NETWR></E1EDP24>";
    }

    private String mapPia(List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        String value = extractAfterQualifier(fields.get(0));
        if (value.isEmpty()) {
            return null;
        }
        return "<E1EDP20><MATNR>" + escapeXml(value) + "</MATNR></E1EDP20>";
    }

    private String[] splitQualifier(String field) {
        if (field == null) {
            return new String[0];
        }
        String trimmed = field.trim();
        return trimmed.split(":", -1);
    }

    private String extractAfterQualifier(String field) {
        String[] parts = splitQualifier(field);
        if (parts.length <= 1) {
            return field == null ? "" : field.trim();
        }
        return parts[1].trim();
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
}
