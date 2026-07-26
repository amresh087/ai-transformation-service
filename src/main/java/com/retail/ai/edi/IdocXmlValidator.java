package com.retail.ai.edi;

// NOTE: adjust package/imports to match your actual project structure.
//
// This does two different kinds of work on purpose:
//
//   1. autoFix(xml)   -- fixes issues that are 100% mechanical and safe to
//      fix with code, with NO risk of guessing wrong: adding a missing
//      BEGIN="1" attribute, adding a missing <TABNAM>, stripping ":::"
//      suffixes off PARTN, zero-padding CREDAT. These never require
//      re-calling the LLM.
//
//   2. validate(xml, previousIdocXml, expectedCredat, expectedParvwCodes)
//      -- checks for issues that CANNOT be safely auto-fixed in code
//      because fixing them requires re-deriving a mapping decision (e.g.
//      "which E1EDP19 code goes with this line item" is exactly the
//      judgment call we're asking the LLM to make), OR checks that need
//      ground-truth values extracted from the original EDI segments
//      (CREDAT must match UNB's date, PARVW must match NAD's qualifier
//      verbatim). These come back as a list of human-readable errors that
//      you feed back into a corrective re-prompt.
 
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
 
public class IdocXmlValidator {
 
    // Matches a leading ```xml / ```XML / ``` fence (with optional trailing
    // newline) and a trailing ``` fence, each optionally preceded/followed
    // by whitespace. Handles the common "model wrapped it in markdown"
    // failure mode that otherwise breaks XML parsing at position 1:1.
    private static final Pattern LEADING_FENCE = Pattern.compile("^\\s*```[a-zA-Z]*\\s*\\n?");
    private static final Pattern TRAILING_FENCE = Pattern.compile("\\n?```\\s*$");
 
    /**
     * Strips a leading/trailing markdown code fence (```xml ... ``` or
     * ``` ... ```) if present. Safe to call on text that has no fence --
     * it's a no-op in that case. ALWAYS call this before autoFix/validate,
     * since the DOM parser will fail immediately on a leading backtick.
     */
    public static String stripCodeFences(String text) {
        if (text == null) {
            return null;
        }
        String result = text.trim();
        result = LEADING_FENCE.matcher(result).replaceFirst("");
        result = TRAILING_FENCE.matcher(result).replaceFirst("");
        return result.trim();
    }
 
    // ---------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------
 
    public static class ValidationResult {
        public final boolean valid;
        public final List<String> errors;
 
        public ValidationResult(boolean valid, List<String> errors) {
            this.valid = valid;
            this.errors = errors;
        }
    }
 
    // ---------------------------------------------------------------
    // 1. Deterministic auto-fix (regex-based, safe, no LLM needed)
    // ---------------------------------------------------------------
 
    private static final Pattern IDOC_OPEN_NO_ATTR = Pattern.compile("<IDOC(\\s*)>");
    private static final Pattern EDI_DC40_OPEN = Pattern.compile("<EDI_DC40>(?!\\s*<TABNAM>)");
    private static final Pattern PARTN_TAG = Pattern.compile("<PARTN>([^<]*)</PARTN>");
    private static final Pattern CREDAT_TAG = Pattern.compile("<CREDAT>([^<]*)</CREDAT>");
    private static final Pattern DOCNUM_TAG = Pattern.compile("<DOCNUM>([^<]*)</DOCNUM>");
 
    public static String autoFix(String xml) {
        String fixed = xml;
 
        // Fix 1: <IDOC> -> <IDOC BEGIN="1">
        fixed = IDOC_OPEN_NO_ATTR.matcher(fixed).replaceAll("<IDOC BEGIN=\"1\">");
 
        // Fix 2: insert <TABNAM>EDI_DC40</TABNAM> if missing right after <EDI_DC40>
        fixed = EDI_DC40_OPEN.matcher(fixed).replaceAll("<EDI_DC40><TABNAM>EDI_DC40</TABNAM>");
 
        // Fix 3: strip everything from the first colon onward in PARTN values
        Matcher partnMatcher = PARTN_TAG.matcher(fixed);
        StringBuilder sb = new StringBuilder();
        while (partnMatcher.find()) {
            String value = partnMatcher.group(1);
            int colonIdx = value.indexOf(':');
            String cleaned = colonIdx >= 0 ? value.substring(0, colonIdx) : value;
            partnMatcher.appendReplacement(sb, Matcher.quoteReplacement("<PARTN>" + cleaned + "</PARTN>"));
        }
        partnMatcher.appendTail(sb);
        fixed = sb.toString();
 
        // Fix 4: zero/century-pad CREDAT if it's 6 digits (YYMMDD -> 20YYMMDD)
        // NOTE: this only catches the 6-digit case. An 8-digit CREDAT that
        // was sourced from the WRONG segment (e.g. DTM instead of UNB)
        // looks structurally valid and can't be caught here -- that's what
        // the expectedCredat check in validate() below is for.
        Matcher credatMatcher = CREDAT_TAG.matcher(fixed);
        sb = new StringBuilder();
        while (credatMatcher.find()) {
            String value = credatMatcher.group(1).trim();
            String corrected = value;
            if (value.matches("\\d{6}")) {
                corrected = "20" + value;
            }
            credatMatcher.appendReplacement(sb, Matcher.quoteReplacement("<CREDAT>" + corrected + "</CREDAT>"));
        }
        credatMatcher.appendTail(sb);
        fixed = sb.toString();
 
        // Fix 5: if DOCNUM is present but not purely numeric, strip it out
        // entirely rather than leave a fabricated value in place. We do NOT
        // invent a replacement here -- that's not something code can do
        // safely without the real UNZ reference number.
        Matcher docnumMatcher = DOCNUM_TAG.matcher(fixed);
        sb = new StringBuilder();
        while (docnumMatcher.find()) {
            String value = docnumMatcher.group(1).trim();
            if (value.matches("\\d+")) {
                String padded = value.length() < 14
                        ? "0".repeat(14 - value.length()) + value
                        : value;
                docnumMatcher.appendReplacement(sb, Matcher.quoteReplacement("<DOCNUM>" + padded + "</DOCNUM>"));
            } else {
                // non-numeric -> drop the tag, don't fabricate a number
                docnumMatcher.appendReplacement(sb, "");
            }
        }
        docnumMatcher.appendTail(sb);
        fixed = sb.toString();
 
        return fixed;
    }
 
    // ---------------------------------------------------------------
    // 2. Structural + semantic validation (DOM-based; requires re-prompt
    //    to fix)
    // ---------------------------------------------------------------
 
    /**
     * Backward-compatible overload: no regression check, no CREDAT/PARVW
     * ground-truth check. Prefer the 4-arg version below whenever you have
     * the source segment data available.
     */
    public static ValidationResult validate(String xml) {
        return validate(xml, null, null, null);
    }
 
    /**
     * Backward-compatible overload: regression check only, no CREDAT/PARVW
     * ground-truth check.
     */
    public static ValidationResult validate(String xml, String previousIdocXml) {
        return validate(xml, previousIdocXml, null, null);
    }
 
    /**
     * @param previousIdocXml    the IDoc XML BEFORE this batch's LLM call, or
     *                           null/blank if this is the very first batch.
     *                           Used to catch the model silently deleting
     *                           previously-built data (e.g. wiping out all
     *                           E1EDP01 line items on a batch that only
     *                           contained UNT/UNZ) -- a real failure mode
     *                           that plain structural checks don't catch,
     *                           since the resulting XML is still well-formed.
     * @param expectedCredat     the 8-digit CREDAT value derived directly
     *                           from the UNB segment's date field (century-
     *                           expanded), or null to skip this check. Every
     *                           CREDAT found in the output must equal this
     *                           exactly -- catches the model sourcing the
     *                           date from DTM (or elsewhere) instead of UNB,
     *                           which produces a structurally valid but
     *                           semantically wrong 8-digit value that
     *                           autoFix's regex can't distinguish from a
     *                           correct one.
     * @param expectedParvwCodes qualifier codes extracted verbatim from
     *                           every NAD segment seen so far (across all
     *                           batches, in order), or null to skip this
     *                           check. Each one must appear as a PARVW
     *                           value somewhere in the output -- catches
     *                           the model "translating" a qualifier (e.g.
     *                           NAD "SE" -> PARVW "SU") instead of copying
     *                           it through unchanged.
     */
    public static ValidationResult validate(String xml, String previousIdocXml,
                                             String expectedCredat,
                                             List<String> expectedParvwCodes) {
        List<String> errors = new ArrayList<>();
 
        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            errors.add("XML is not well-formed / failed to parse: " + e.getMessage());
            return new ValidationResult(false, errors);
        }
 
        // IDOC BEGIN attribute
        NodeList idocNodes = doc.getElementsByTagName("IDOC");
        if (idocNodes.getLength() == 0) {
            errors.add("Missing <IDOC> element entirely.");
        } else {
            Element idoc = (Element) idocNodes.item(0);
            if (!idoc.hasAttribute("BEGIN") || idoc.getAttribute("BEGIN").isBlank()) {
                errors.add("<IDOC> is missing the required BEGIN=\"1\" attribute.");
            }
        }
 
        // TABNAM
        NodeList tabnamNodes = doc.getElementsByTagName("TABNAM");
        if (tabnamNodes.getLength() == 0) {
            errors.add("Missing <TABNAM>EDI_DC40</TABNAM> inside <EDI_DC40>.");
        }
 
        // CREDAT length (should be 8 digits, YYYYMMDD)
        NodeList credatNodes = doc.getElementsByTagName("CREDAT");
        for (int i = 0; i < credatNodes.getLength(); i++) {
            String value = credatNodes.item(i).getTextContent().trim();
            if (!value.matches("\\d{8}")) {
                errors.add("CREDAT value \"" + value + "\" is not an 8-digit YYYYMMDD date "
                        + "(EDI_DC40/CREDAT must be century-expanded, e.g. \"20\" + YYMMDD).");
            }
        }
 
        // FIX #1: CREDAT must match the UNB interchange date specifically,
        // not just "some" 8-digit date. This catches the case where the
        // model sourced CREDAT from DTM (or another date-bearing segment)
        // instead of UNB -- structurally valid, semantically wrong, and
        // NOT caught by the format check above.
        if (expectedCredat != null && !expectedCredat.isBlank()) {
            for (int i = 0; i < credatNodes.getLength(); i++) {
                String value = credatNodes.item(i).getTextContent().trim();
                if (!value.equals(expectedCredat)) {
                    errors.add("CREDAT value \"" + value + "\" does not match the UNB "
                            + "interchange date (\"" + expectedCredat + "\"). CREDAT/CRETIM "
                            + "must be derived from the UNB segment's date/time field, never "
                            + "from DTM or any other segment.");
                }
            }
        }
 
        // PARTN should never contain a colon
        NodeList partnNodes = doc.getElementsByTagName("PARTN");
        for (int i = 0; i < partnNodes.getLength(); i++) {
            String value = partnNodes.item(i).getTextContent();
            if (value.contains(":")) {
                errors.add("PARTN value \"" + value + "\" still contains a colon-suffix; "
                        + "everything from the first colon onward must be stripped.");
            }
        }
 
        // FIX #2: every NAD-derived qualifier we've seen so far must appear
        // verbatim as a PARVW value somewhere in the output. Catches the
        // model "translating" a qualifier (e.g. "SE" -> "SU") instead of
        // copying it through unchanged -- a silent semantic substitution
        // that produces well-formed, plausible-looking output.
        if (expectedParvwCodes != null && !expectedParvwCodes.isEmpty()) {
            NodeList parvwNodes = doc.getElementsByTagName("PARVW");
            List<String> actualParvwValues = new ArrayList<>();
            for (int i = 0; i < parvwNodes.getLength(); i++) {
                actualParvwValues.add(parvwNodes.item(i).getTextContent().trim());
            }
            for (String qualifier : expectedParvwCodes) {
                if (!actualParvwValues.contains(qualifier)) {
                    errors.add("Expected PARVW=\"" + qualifier + "\" (copied verbatim from the "
                            + "source NAD segment) but it's missing from the output -- the "
                            + "qualifier may have been reinterpreted/translated instead of "
                            + "copied through as-is.");
                }
            }
        }
 
        // DOCNUM must be numeric and 14 digits if present and non-empty
        NodeList docnumNodes = doc.getElementsByTagName("DOCNUM");
        for (int i = 0; i < docnumNodes.getLength(); i++) {
            String value = docnumNodes.item(i).getTextContent().trim();
            if (!value.isEmpty() && !value.matches("\\d{14}")) {
                errors.add("DOCNUM value \"" + value + "\" is not a 14-digit zero-padded number; "
                        + "DOCNUM must only be set from a real UNZ reference, never invented.");
            }
        }
 
        // Every E1EDP01 must contain at least one E1EDP19 child
        NodeList lineItems = doc.getElementsByTagName("E1EDP01");
        for (int i = 0; i < lineItems.getLength(); i++) {
            Element lineItem = (Element) lineItems.item(i);
            NodeList e1edp19 = lineItem.getElementsByTagName("E1EDP19");
            String posex = firstChildText(lineItem, "POSEX");
            if (e1edp19.getLength() == 0) {
                errors.add("E1EDP01 with POSEX=\"" + posex + "\" has no nested E1EDP19 "
                        + "(item code) -- the LIN item code was dropped.");
            }
        }
 
        // Heuristic-only check for fix #3: flag (don't hard-fail on) two
        // different line items sharing identical MENGE+NETWR, since that's
        // the signature of the cross-item QTY/PRI bleed-over bug -- but two
        // items COULD legitimately have the same quantity/price by
        // coincidence, so this is a warning, not an error, and doesn't
        // block the merge.
        Map<String, List<String>> mengeNetwrToPosex = new HashMap<>();
        for (int i = 0; i < lineItems.getLength(); i++) {
            Element lineItem = (Element) lineItems.item(i);
            String posex = firstChildText(lineItem, "POSEX");
            String key = firstChildText(lineItem, "MENGE") + "|" + firstChildText(lineItem, "NETWR");
            mengeNetwrToPosex.computeIfAbsent(key, k -> new ArrayList<>()).add(posex);
        }
        mengeNetwrToPosex.forEach((key, posexList) -> {
            if (posexList.size() > 1 && lineItems.getLength() > 1) {
                System.out.println("---->SUSPECT CORRELATION (non-blocking): POSEX " + posexList
                        + " share identical MENGE|NETWR (" + key + ") -- verify these weren't "
                        + "both mapped from the same QTY/PRI segment by mistake.");
            }
        });
 
        // No leftover EDI-source tag names anywhere in the output
        String[] forbiddenTags = {"UNB", "UNH", "UNT", "UNZ", "NAD", "DTM", "LIN",
                "PIA", "IMD", "QTY", "PRI", "CUX", "BGM", "SENDERID", "RECEIVERID",
                "segment", "field"};
        for (String tag : forbiddenTags) {
            if (doc.getElementsByTagName(tag).getLength() > 0) {
                errors.add("Forbidden EDI-source tag <" + tag + "> appeared in the output; "
                        + "it must be translated into the corresponding IDoc field, never emitted as-is.");
            }
        }
 
        // No-regression check: nothing that existed in the previous IDoc
        // should silently disappear. This is what would have caught the
        // batch-4-wipes-out-all-line-items failure.
        if (previousIdocXml != null && !previousIdocXml.isBlank()) {
            try {
                Document prevDoc = parseQuietly(previousIdocXml);
                if (prevDoc != null) {
                    checkNoRegression(prevDoc, doc, "E1EDP01", "POSEX", errors);
                    checkNoRegression(prevDoc, doc, "E1EDKA1", "PARVW", errors);
                }
            } catch (Exception e) {
                // Previous IDoc itself didn't parse (shouldn't happen since
                // it would have failed validation when it was produced) --
                // don't block on this, just skip the regression check.
            }
        }
 
        return new ValidationResult(errors.isEmpty(), errors);
    }
 
    private static Document parseQuietly(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
 
    /**
     * Confirms every distinct value of {@code keyTagName} (e.g. POSEX for
     * E1EDP01, PARVW for E1EDKA1) found under {@code elementTagName} in
     * {@code prevDoc} is still present somewhere under the same tag in
     * {@code newDoc}. Flags each one that vanished.
     */
    private static void checkNoRegression(Document prevDoc, Document newDoc,
                                           String elementTagName, String keyTagName,
                                           List<String> errors) {
        List<String> prevKeys = collectKeys(prevDoc, elementTagName, keyTagName);
        List<String> newKeys = collectKeys(newDoc, elementTagName, keyTagName);
 
        for (String key : prevKeys) {
            if (!newKeys.contains(key)) {
                errors.add("REGRESSION: <" + elementTagName + "> with " + keyTagName + "=\"" + key
                        + "\" existed in the previous IDoc but is MISSING from this response. "
                        + "Never delete previously-generated IDoc content -- preserve it exactly "
                        + "and only ADD to it.");
            }
        }
    }
 
    private static List<String> collectKeys(Document doc, String elementTagName, String keyTagName) {
        List<String> keys = new ArrayList<>();
        NodeList elements = doc.getElementsByTagName(elementTagName);
        for (int i = 0; i < elements.getLength(); i++) {
            Element el = (Element) elements.item(i);
            keys.add(firstChildText(el, keyTagName));
        }
        return keys;
    }
 
 
    private static String firstChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) {
            return "?";
        }
        Node node = children.item(0);
        return node.getTextContent();
    }
 
    // ---------------------------------------------------------------
    // 3. Build a corrective re-prompt from validation errors
    // ---------------------------------------------------------------
 
    public static String buildCorrectivePrompt(String previousResponseXml, List<String> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous response has the following specific defects. ")
          .append("Return the FULL corrected IDoc XML (same rules as before: ")
          .append("no explanation, no markdown, well-formed, full envelope). ")
          .append("Fix ONLY what is listed below; do not change anything else ")
          .append("that was already correct.\n\n");
 
        sb.append("DEFECTS TO FIX:\n");
        int i = 1;
        for (String error : errors) {
            sb.append(i++).append(". ").append(error).append("\n");
        }
 
        sb.append("\nPREVIOUS (DEFECTIVE) RESPONSE:\n");
        sb.append(previousResponseXml).append("\n");
 
        return sb.toString();
    }
}
 
