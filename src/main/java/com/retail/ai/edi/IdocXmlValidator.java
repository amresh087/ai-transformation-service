package com.retail.ai.edi;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

// NOTE: adjust package/imports to match your actual project structure.
//
// CHANGES IN THIS VERSION (fixes for the SU-party / ITEM1003 bugs):
//
//   1. validate(...) gained two new checks:
//        a) "extraneous PARVW" -- any PARVW in the output that is NOT in
//           expectedParvwCodes is now an error, not just a missing one.
//           This is what would have caught the fabricated PARVW="SU"
//           E1EDKA1 that had no corresponding source NAD segment at all.
//        b) content-aware regression -- checkNoRegression previously only
//           confirmed a POSEX/PARVW *key* still existed. It did NOT check
//           that the *content* under that key was unchanged, which is
//           exactly how POSEX="1" got silently swapped from
//           (ITEM1001/ITEM1001A, NETWR 15.50, WIDGET) to a fabricated
//           (ITEM1003, NETWR 40.00, no text) while still reporting
//           "no regression" because the key "1" was technically present.
//           checkNoRegressionContent below fixes this by diffing scalar
//           fields exactly and repeating child groups (E1EDP19/E1EDP20/
//           E1EDPT1) as a subset requirement.
//
//   2. buildCorrectivePrompt(...) now takes the ORIGINAL batch segments'
//      raw text and the idocBeforeBatch snapshot, and includes them
//      verbatim in the corrective re-prompt. Previously the corrective
//      prompt contained ONLY the defective draft + a list of error
//      *descriptions*, with no access to the real source data needed to
//      fix them -- which is why corrective retries tended to guess/
//      fabricate values (e.g. inventing ITEM1003/NETWR=40.00 to satisfy
//      "POSEX=1 must exist") instead of recovering the real ones.
//      The old 2-arg overload is kept but deprecated so any other caller
//      doesn't silently keep using the ground-truth-blind version.

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class IdocXmlValidator {

    private static final Pattern LEADING_FENCE = Pattern.compile("^\\s*```[a-zA-Z]*\\s*\\n?");
    private static final Pattern TRAILING_FENCE = Pattern.compile("\\n?```\\s*$");

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
    // 1. Deterministic auto-fix (unchanged from before)
    // ---------------------------------------------------------------

    private static final Pattern IDOC_OPEN_NO_ATTR = Pattern.compile("<IDOC(\\s*)>");
    private static final Pattern EDI_DC40_OPEN = Pattern.compile("<EDI_DC40>(?!\\s*<TABNAM>)");
    private static final Pattern PARTN_TAG = Pattern.compile("<PARTN>([^<]*)</PARTN>");
    private static final Pattern CREDAT_TAG = Pattern.compile("<CREDAT>([^<]*)</CREDAT>");
    private static final Pattern DOCNUM_TAG = Pattern.compile("<DOCNUM>([^<]*)</DOCNUM>");

    public static String autoFix(String xml) {
        String fixed = xml;

        fixed = IDOC_OPEN_NO_ATTR.matcher(fixed).replaceAll("<IDOC BEGIN=\"1\">");
        fixed = EDI_DC40_OPEN.matcher(fixed).replaceAll("<EDI_DC40><TABNAM>EDI_DC40</TABNAM>");

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
                docnumMatcher.appendReplacement(sb, "");
            }
        }
        docnumMatcher.appendTail(sb);
        fixed = sb.toString();

        return fixed;
    }

    // ---------------------------------------------------------------
    // 2. Structural + semantic validation
    // ---------------------------------------------------------------

    public static ValidationResult validate(String xml) {
        return validate(xml, null, null, null, null);
    }

    public static ValidationResult validate(String xml, String previousIdocXml) {
        return validate(xml, previousIdocXml, null, null, null);
    }

    /** Backward-compatible 4-arg overload -- no expectedPosexCodes check. */
    public static ValidationResult validate(String xml, String previousIdocXml,
                                             String expectedCredat,
                                             List<String> expectedParvwCodes) {
        return validate(xml, previousIdocXml, expectedCredat, expectedParvwCodes, null, null);
    }

    /**
     * Backward-compatible 5-arg overload -- no batch segment expectations.
     */
    public static ValidationResult validate(String xml, String previousIdocXml,
                                             String expectedCredat,
                                             List<String> expectedParvwCodes,
                                             List<String> expectedPosexCodes) {
        return validate(xml, previousIdocXml, expectedCredat, expectedParvwCodes, expectedPosexCodes, null);
    }

    /**
     * @param expectedPosexCodes literal LIN line-numbers seen so far
     *                           (across all batches, in order), used the
     *                           same way expectedParvwCodes is used for
     *                           PARVW: to flag any POSEX in the output that
     *                           doesn't correspond to a real source LIN
     *                           line number. Pass null/empty to skip this
     *                           check (e.g. if the caller doesn't track it).
     * @param batchSegments      the original raw batch segments, used to
     *                           validate expected quantity, expected item
     *                           code counts, and expected line-item text.
     */
    public static ValidationResult validate(String xml, String previousIdocXml,
                                             String expectedCredat,
                                             List<String> expectedParvwCodes,
                                             List<String> expectedPosexCodes,
                                             List<EdiSegment> batchSegments) {
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

        NodeList idocNodes = doc.getElementsByTagName("IDOC");
        if (idocNodes.getLength() == 0) {
            errors.add("Missing <IDOC> element entirely.");
        } else {
            Element idoc = (Element) idocNodes.item(0);
            if (!idoc.hasAttribute("BEGIN") || idoc.getAttribute("BEGIN").isBlank()) {
                errors.add("<IDOC> is missing the required BEGIN=\"1\" attribute.");
            }
        }

        NodeList tabnamNodes = doc.getElementsByTagName("TABNAM");
        if (tabnamNodes.getLength() == 0) {
            errors.add("Missing <TABNAM>EDI_DC40</TABNAM> inside <EDI_DC40>.");
        }

        NodeList credatNodes = doc.getElementsByTagName("CREDAT");
        for (int i = 0; i < credatNodes.getLength(); i++) {
            String value = credatNodes.item(i).getTextContent().trim();
            if (!value.matches("\\d{8}")) {
                errors.add("CREDAT value \"" + value + "\" is not an 8-digit YYYYMMDD date "
                        + "(EDI_DC40/CREDAT must be century-expanded, e.g. \"20\" + YYMMDD).");
            }
        }

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

        NodeList partnNodes = doc.getElementsByTagName("PARTN");
        for (int i = 0; i < partnNodes.getLength(); i++) {
            String value = partnNodes.item(i).getTextContent();
            if (value.contains(":")) {
                errors.add("PARTN value \"" + value + "\" still contains a colon-suffix; "
                        + "everything from the first colon onward must be stripped.");
            }
        }

        // Every expected NAD-derived qualifier must appear verbatim.
        List<String> actualParvwValues = new ArrayList<>();
        NodeList parvwNodes = doc.getElementsByTagName("PARVW");
        for (int i = 0; i < parvwNodes.getLength(); i++) {
            actualParvwValues.add(parvwNodes.item(i).getTextContent().trim());
        }
        if (expectedParvwCodes != null && !expectedParvwCodes.isEmpty()) {
            for (String qualifier : expectedParvwCodes) {
                if (!actualParvwValues.contains(qualifier)) {
                    errors.add("Expected PARVW=\"" + qualifier + "\" (copied verbatim from the "
                            + "source NAD segment) but it's missing from the output -- the "
                            + "qualifier may have been reinterpreted/translated instead of "
                            + "copied through as-is.");
                }
            }

            // FIX (new): the inverse check. Anything in the output NOT in
            // the expected set has no traceable source NAD segment at all
            // -- this is how a fabricated E1EDKA1 (e.g. PARVW="SU" invented
            // during a corrective retry that had no real NAD to copy from)
            // slips through undetected by the "missing expected value"
            // check above, since that check only ever looks for absence,
            // never for extras.
            for (String actual : actualParvwValues) {
                if (!expectedParvwCodes.contains(actual)) {
                    errors.add("PARVW=\"" + actual + "\" appears in the output but does not "
                            + "correspond to any real source NAD segment qualifier seen so far "
                            + "(" + expectedParvwCodes + "). This party appears to be fabricated "
                            + "-- remove it unless it is genuinely backed by a NAD segment in "
                            + "the current batch.");
                }
            }
        }

        // Same idea for POSEX: every value must trace back to a real LIN
        // line number seen so far; anything else is fabricated. Every expected
        // source POSEX must also be present in the output.
        if (expectedPosexCodes != null && !expectedPosexCodes.isEmpty()) {
            NodeList lineItemsForPosexCheck = doc.getElementsByTagName("E1EDP01");
            List<String> actualPosexValues = new ArrayList<>();
            for (int i = 0; i < lineItemsForPosexCheck.getLength(); i++) {
                String posex = firstChildText((Element) lineItemsForPosexCheck.item(i), "POSEX");
                actualPosexValues.add(posex);
                if (!expectedPosexCodes.contains(posex)) {
                    errors.add("POSEX=\"" + posex + "\" appears in the output but does not "
                            + "correspond to any real source LIN line number seen so far "
                            + "(" + expectedPosexCodes + "). This line item appears to be "
                            + "fabricated.");
                }
            }
            for (String expected : expectedPosexCodes) {
                if (!actualPosexValues.contains(expected)) {
                    errors.add("Expected E1EDP01/POSEX=\"" + expected + "\" but it is missing from the output. "
                            + "Preserve every source LIN line number, including any new ones introduced by this batch.");
                }
            }
        }

        if (batchSegments != null && !batchSegments.isEmpty()) {
            BatchExpectations expectations = inferBatchExpectations(batchSegments);
            validateBatchExpectations(doc, expectations, errors);
        }

        NodeList docnumNodes = doc.getElementsByTagName("DOCNUM");
        for (int i = 0; i < docnumNodes.getLength(); i++) {
            String value = docnumNodes.item(i).getTextContent().trim();
            if (!value.isEmpty() && !value.matches("\\d{14}")) {
                errors.add("DOCNUM value \"" + value + "\" is not a 14-digit zero-padded number; "
                        + "DOCNUM must only be set from a real UNZ reference, never invented.");
            }
        }

        NodeList lineItems = doc.getElementsByTagName("E1EDP01");
        List<String> posexOrder = new ArrayList<>();
        boolean allNumericPosex = true;
        for (int i = 0; i < lineItems.getLength(); i++) {
            Element lineItem = (Element) lineItems.item(i);
            NodeList e1edp19 = lineItem.getElementsByTagName("E1EDP19");
            String posex = firstChildText(lineItem, "POSEX");
            posexOrder.add(posex);
            if (!posex.matches("\\d+")) {
                allNumericPosex = false;
            }
            if (e1edp19.getLength() == 0) {
                errors.add("E1EDP01 with POSEX=\"" + posex + "\" has no nested E1EDP19 "
                        + "(item code) -- the LIN item code was dropped.");
            }
        }

        if (allNumericPosex && posexOrder.size() > 1) {
            int previousValue = Integer.MIN_VALUE;
            for (String posex : posexOrder) {
                int currentValue = Integer.parseInt(posex);
                if (previousValue != Integer.MIN_VALUE && currentValue < previousValue) {
                    errors.add("E1EDP01 segments are out of order by POSEX. Preserve the source line item order and do not emit POSEX=" + currentValue + " before POSEX=" + previousValue + ".");
                    break;
                }
                previousValue = currentValue;
            }
        }

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

        String[] forbiddenTags = {"UNB", "UNH", "UNT", "UNZ", "NAD", "DTM", "LIN",
                "PIA", "IMD", "QTY", "PRI", "CUX", "BGM", "SENDERID", "RECEIVERID",
                "segment", "field"};
        for (String tag : forbiddenTags) {
            if (doc.getElementsByTagName(tag).getLength() > 0) {
                errors.add("Forbidden EDI-source tag <" + tag + "> appeared in the output; "
                        + "it must be translated into the corresponding IDoc field, never emitted as-is.");
            }
        }

        // No-regression checks -- BOTH key-presence AND content.
        if (previousIdocXml != null && !previousIdocXml.isBlank()) {
            try {
                Document prevDoc = parseQuietly(previousIdocXml);
                if (prevDoc != null) {
                    checkNoRegression(prevDoc, doc, "E1EDP01", "POSEX", errors);
                    checkNoRegression(prevDoc, doc, "E1EDKA1", "PARVW", errors);

                    // FIX (new): content-level diff, not just key presence.
                    // Catches e.g. POSEX="1" still existing but its
                    // MENGE/E1EDP19/E1EDP20/E1EDPT1 content having been
                    // silently swapped out for fabricated values.
                    checkNoRegressionContent(prevDoc, doc, "E1EDP01", "POSEX",
                            new String[] {"MENGE", "MENEE"},
                            new String[] {"E1EDP19", "E1EDP20", "E1EDPT1"},
                            errors);
                    checkNoRegressionContent(prevDoc, doc, "E1EDKA1", "PARVW",
                            new String[] {"PARTN"},
                            new String[] {},
                            errors);
                }
            } catch (Exception e) {
                // previous IDoc itself didn't parse -- skip, don't block
            }
        }

        if (batchSegments != null && !batchSegments.isEmpty()) {
            BatchExpectations expectations = inferBatchExpectations(batchSegments);
            validateBatchExpectations(doc, expectations, errors);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    private static BatchExpectations inferBatchExpectations(List<EdiSegment> segments) {
        BatchExpectations expectations = new BatchExpectations();
        String currentLine = null;
        List<String> lineOrder = new ArrayList<>();

        for (EdiSegment segment : segments) {
            String name = segment.getName();
            List<String> fields = segment.getFields();
            if ("NAD".equals(name)) {
                if (!fields.isEmpty()) {
                    expectations.expectedParvwQualifiers.add(fields.get(0).trim());
                }
                continue;
            }

            if ("LIN".equals(name)) {
                for (int i = 0; i + 1 < fields.size(); i += 2) {
                    String lineNumber = fields.get(i).trim();
                    if (!lineNumber.matches("\\d+")) {
                        continue;
                    }
                    String codeQualifier = fields.get(i + 1).trim();
                    if (!expectations.lineExpectations.containsKey(lineNumber)) {
                        expectations.lineExpectations.put(lineNumber, new LineExpectation(lineNumber));
                        lineOrder.add(lineNumber);
                    }
                    expectations.lineExpectations.get(lineNumber).needsItemCode = !codeQualifier.isBlank();
                    currentLine = lineNumber;
                }
                continue;
            }

            if ("PIA".equals(name)) {
                if (!fields.isEmpty() && currentLine != null) {
                    expectations.lineExpectations
                            .computeIfAbsent(currentLine, LineExpectation::new)
                            .needsItemCode = true;
                }
                continue;
            }

            if ("IMD".equals(name)) {
                if (!fields.isEmpty() && currentLine != null) {
                    LineExpectation expectation = expectations.lineExpectations
                            .computeIfAbsent(currentLine, LineExpectation::new);
                    expectation.needsText = true;
                    String value = fields.get(fields.size() - 1).trim();
                    int lastColon = value.lastIndexOf(':');
                    if (lastColon >= 0 && lastColon < value.length() - 1) {
                        expectation.expectedText = value.substring(lastColon + 1).trim();
                    } else {
                        expectation.expectedText = value;
                    }
                }
                continue;
            }

            if ("QTY".equals(name)) {
                List<String> quantityEntries = new ArrayList<>();
                for (String raw : fields) {
                    if (!raw.isBlank()) {
                        quantityEntries.add(raw.trim());
                    }
                }
                if (!quantityEntries.isEmpty()) {
                    if (quantityEntries.size() == 1 && currentLine != null) {
                        parseQuantityEntry(quantityEntries.get(0), expectations
                                .computeLineExpectation(currentLine));
                    } else {
                        int index = 0;
                        for (String entry : quantityEntries) {
                            if (index < lineOrder.size()) {
                                parseQuantityEntry(entry, expectations
                                        .computeLineExpectation(lineOrder.get(index)));
                            } else if (currentLine != null) {
                                parseQuantityEntry(entry, expectations
                                        .computeLineExpectation(currentLine));
                            }
                            index++;
                        }
                    }
                }
                continue;
            }

            if ("PRI".equals(name)) {
                List<String> priceEntries = new ArrayList<>();
                for (String raw : fields) {
                    if (!raw.isBlank()) {
                        priceEntries.add(raw.trim());
                    }
                }
                if (!priceEntries.isEmpty()) {
                    if (priceEntries.size() == 1 && currentLine != null) {
                        expectations.computeLineExpectation(currentLine).expectedNetwr = parsePriceValue(priceEntries.get(0));
                    } else {
                        int index = 0;
                        for (String entry : priceEntries) {
                            if (index < lineOrder.size()) {
                                expectations.computeLineExpectation(lineOrder.get(index)).expectedNetwr = parsePriceValue(entry);
                            } else if (currentLine != null) {
                                expectations.computeLineExpectation(currentLine).expectedNetwr = parsePriceValue(entry);
                            }
                            index++;
                        }
                    }
                }
            }
        }

        return expectations;
    }

    private static void parseQuantityEntry(String entry, LineExpectation expectation) {
        String[] parts = entry.split(":", -1);
        if (parts.length >= 3) {
            expectation.expectedAmount = parts[1].trim();
            expectation.expectedUnit = parts[2].trim();
            expectation.needsQuantity = true;
        }
    }

    private static String parsePriceValue(String entry) {
        String[] parts = entry.split(":", -1);
        return parts.length >= 2 ? parts[1].trim() : null;
    }

    private static void validateBatchExpectations(Document doc, BatchExpectations expectations, List<String> errors) {
        for (String qualifier : expectations.expectedParvwQualifiers) {
            boolean found = false;
            NodeList parvwNodes = doc.getElementsByTagName("PARVW");
            for (int i = 0; i < parvwNodes.getLength(); i++) {
                if (qualifier.equals(parvwNodes.item(i).getTextContent().trim())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add("Expected E1EDKA1/PARVW=\"" + qualifier + "\" from the batch's NAD segment, but it is missing from the output.");
            }
        }

        for (LineExpectation expectation : expectations.lineExpectations.values()) {
            Element lineItem = findLineItemByPosex(doc, expectation.lineNumber);
            if (lineItem == null) {
                errors.add("Expected an E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" based on the batch's LIN/PIA segments, but it is missing.");
                continue;
            }
            if (expectation.needsItemCode) {
                NodeList e1edp19 = lineItem.getElementsByTagName("E1EDP19");
                if (e1edp19.getLength() == 0) {
                    errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" should include at least one E1EDP19 item code derived from LIN/PIA, but none was found.");
                }
            }
            if (expectation.needsText) {
                NodeList e1edpt1 = lineItem.getElementsByTagName("E1EDPT1");
                if (e1edpt1.getLength() == 0) {
                    errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" should include an E1EDPT1/TDLINE description derived from IMD, but none was found.");
                } else if (expectation.expectedText != null && !expectation.expectedText.isBlank()) {
                    String actualText = firstChildText((Element) e1edpt1.item(0), "TDLINE");
                    if (!expectation.expectedText.equals(actualText)) {
                        errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" has E1EDPT1/TDLINE=\"" + actualText + "\" but expected \"" + expectation.expectedText + "\" from the batch's IMD.");
                    }
                }
            }
            if (expectation.needsQuantity) {
                String menge = firstChildText(lineItem, "MENGE");
                String menee = firstChildText(lineItem, "MENEE");
                if (menge.isBlank() || menee.isBlank()) {
                    errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" should include quantity MENGE/MENEE derived from QTY, but one or both were missing.");
                } else {
                    if (expectation.expectedAmount != null && !expectation.expectedAmount.equals(menge)) {
                        errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" has MENGE=\"" + menge + "\" but expected \"" + expectation.expectedAmount + "\" from the batch's QTY.");
                    }
                    if (expectation.expectedUnit != null && !expectation.expectedUnit.equals(menee)) {
                        errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" has MENEE=\"" + menee + "\" but expected \"" + expectation.expectedUnit + "\" from the batch's QTY.");
                    }
                }
            }
            if (expectation.expectedNetwr != null) {
                String netwr = firstChildText(lineItem, "NETWR");
                if (netwr.isBlank()) {
                    errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" should include E1EDP20/NETWR derived from PRI, but it was missing.");
                } else if (!expectation.expectedNetwr.equals(netwr)) {
                    errors.add("E1EDP01 with POSEX=\"" + expectation.lineNumber + "\" has NETWR=\"" + netwr + "\" but expected \"" + expectation.expectedNetwr + "\" from the batch's PRI.");
                }
            }
        }
    }

    private static Element findLineItemByPosex(Document doc, String posex) {
        NodeList lineItems = doc.getElementsByTagName("E1EDP01");
        for (int i = 0; i < lineItems.getLength(); i++) {
            Element lineItem = (Element) lineItems.item(i);
            if (posex.equals(firstChildText(lineItem, "POSEX"))) {
                return lineItem;
            }
        }
        return null;
    }

    private static class BatchExpectations {
        final Map<String, LineExpectation> lineExpectations = new LinkedHashMap<>();
        final List<String> expectedParvwQualifiers = new ArrayList<>();

        LineExpectation computeLineExpectation(String lineNumber) {
            return lineExpectations.computeIfAbsent(lineNumber, LineExpectation::new);
        }
    }

    private static class LineExpectation {
        final String lineNumber;
        boolean needsItemCode = false;
        boolean needsText = false;
        boolean needsQuantity = false;
        String expectedText;
        String expectedAmount;
        String expectedUnit;
        String expectedNetwr;

        LineExpectation(String lineNumber) {
            this.lineNumber = lineNumber;
        }
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

    /**
     * Content-aware companion to checkNoRegression: for every element in
     * prevDoc matched to an element in newDoc by the same key value, this
     * verifies:
     *   (a) each of {@code strictScalarChildTags} is UNCHANGED (exact
     *       string match) -- these are non-repeating fields where any
     *       difference means the content was mutated, not extended.
     *   (b) each instance of each of {@code repeatingChildGroupTags} that
     *       existed in prevDoc's element is still present as an
     *       equivalent instance (by full serialized text) somewhere among
     *       newDoc's matching element's children of that tag -- this
     *       allows legitimate ADDITION of new instances (e.g. a later
     *       batch's PIA adding a second E1EDP19) without allowing removal
     *       or replacement of the ones that were already there.
     */
    private static void checkNoRegressionContent(Document prevDoc, Document newDoc,
                                                  String elementTagName, String keyTagName,
                                                  String[] strictScalarChildTags,
                                                  String[] repeatingChildGroupTags,
                                                  List<String> errors) {
        Map<String, Element> prevByKey = mapByKey(prevDoc, elementTagName, keyTagName);
        Map<String, Element> newByKey = mapByKey(newDoc, elementTagName, keyTagName);

        for (Map.Entry<String, Element> entry : prevByKey.entrySet()) {
            String key = entry.getKey();
            Element prevEl = entry.getValue();
            Element newEl = newByKey.get(key);
            if (newEl == null) {
                continue; // already reported by checkNoRegression
            }

            for (String scalarTag : strictScalarChildTags) {
                String prevValue = firstChildText(prevEl, scalarTag);
                String newValue = firstChildText(newEl, scalarTag);
                if (!prevValue.equals(newValue)) {
                    errors.add("REGRESSION (content changed): <" + elementTagName + "> with "
                            + keyTagName + "=\"" + key + "\" had " + scalarTag + "=\"" + prevValue
                            + "\" in the previous IDoc but now has " + scalarTag + "=\"" + newValue
                            + "\". Never overwrite an existing " + elementTagName
                            + "'s values -- only append NEW sibling/child elements to it.");
                }
            }

            for (String groupTag : repeatingChildGroupTags) {
                Set<String> prevInstances = serializedChildInstances(prevEl, groupTag);
                Set<String> newInstances = serializedChildInstances(newEl, groupTag);
                for (String prevInstance : prevInstances) {
                    if (!newInstances.contains(prevInstance)) {
                        errors.add("REGRESSION (content changed): <" + elementTagName + "> with "
                                + keyTagName + "=\"" + key + "\" previously had a <" + groupTag
                                + "> [" + prevInstance + "] that is now missing or altered. "
                                + "Never remove or overwrite an existing " + groupTag
                                + " -- only add new ones alongside it.");
                    }
                }
            }
        }
    }

    private static Map<String, Element> mapByKey(Document doc, String elementTagName, String keyTagName) {
        Map<String, Element> map = new java.util.LinkedHashMap<>();
        NodeList elements = doc.getElementsByTagName(elementTagName);
        for (int i = 0; i < elements.getLength(); i++) {
            Element el = (Element) elements.item(i);
            String key = firstChildText(el, keyTagName);
            // First occurrence wins if duplicate keys exist (shouldn't happen
            // in a well-behaved IDoc, but don't let it crash validation).
            map.putIfAbsent(key, el);
        }
        return map;
    }

    /**
     * Serializes each direct child of {@code parent} named {@code childTag}
     * into a stable "tag=value|tag=value" string (sorted by tag name) so
     * two structurally-equivalent instances compare equal regardless of
     * child element ordering.
     */
    private static Set<String> serializedChildInstances(Element parent, String childTag) {
        Set<String> result = new LinkedHashSet<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && childTag.equals(node.getNodeName())) {
                Element childEl = (Element) node;
                Map<String, String> fields = new HashMap<>();
                NodeList grandchildren = childEl.getChildNodes();
                for (int j = 0; j < grandchildren.getLength(); j++) {
                    Node gc = grandchildren.item(j);
                    if (gc.getNodeType() == Node.ELEMENT_NODE) {
                        fields.put(gc.getNodeName(), gc.getTextContent().trim());
                    }
                }
                List<String> keys = new ArrayList<>(fields.keySet());
                keys.sort(String::compareTo);
                StringBuilder sb = new StringBuilder();
                for (String k : keys) {
                    if (sb.length() > 0) sb.append("|");
                    sb.append(k).append("=").append(fields.get(k));
                }
                result.add(sb.toString());
            }
        }
        return result;
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

    /**
     * @deprecated Ground-truth-blind version kept only for source
     * compatibility. This is the version that caused the fabrication bugs
     * (ITEM1003, PARVW="SU") because a retry built from this had no real
     * source segments or prior IDoc snapshot to recover correct values
     * from -- only the defective draft and a description of what's wrong.
     * Use the 4-arg overload below in all new call sites.
     */
    @Deprecated
    public static String buildCorrectivePrompt(String previousResponseXml, List<String> errors) {
        return buildCorrectivePrompt(previousResponseXml, errors, null, null);
    }

    /**
     * @param previousResponseXml the defective draft to fix
     * @param errors               validation errors to fix
     * @param idocBeforeBatch      the IDoc snapshot as it existed BEFORE
     *                             this batch's original call -- the source
     *                             of truth for any "REGRESSION" /
     *                             "fabricated" error, so the model can
     *                             recover the real previous values instead
     *                             of inventing plausible-looking ones.
     * @param rawBatchSegmentsText the ORIGINAL EDI segments for this batch,
     *                             verbatim (e.g. re-join each
     *                             EdiSegment.getRawXml() the same way the
     *                             initial prompt did) -- the source of
     *                             truth for any "missing PARVW/POSEX" or
     *                             "dropped item code" error.
     */
    public static String buildCorrectivePrompt(String previousResponseXml, List<String> errors,
                                                String idocBeforeBatch, String rawBatchSegmentsText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Your previous response has the following specific defects. ")
          .append("Return the FULL corrected IDoc XML (same rules as before: ")
          .append("no explanation, no markdown, well-formed, full envelope). ")
          .append("Fix ONLY what is listed below; do not change anything else ")
          .append("that was already correct. Every value you use to fix these ")
          .append("defects MUST come from the ORIGINAL SOURCE SEGMENTS and/or ")
          .append("PREVIOUSLY-CONFIRMED IDOC sections below -- never invent, ")
          .append("guess, or reuse a value from an unrelated line item just to ")
          .append("satisfy an existence check.\n\n");

        sb.append("DEFECTS TO FIX:\n");
        int i = 1;
        for (String error : errors) {
            sb.append(i++).append(". ").append(error).append("\n");
        }

        if (idocBeforeBatch != null && !idocBeforeBatch.isBlank()) {
            sb.append("\nPREVIOUSLY-CONFIRMED IDOC (ground truth for anything flagged as ")
              .append("REGRESSION or fabricated -- copy exact prior values from here, do not ")
              .append("recreate them from memory):\n");
            sb.append(idocBeforeBatch).append("\n");
        }

        if (rawBatchSegmentsText != null && !rawBatchSegmentsText.isBlank()) {
            sb.append("\nORIGINAL SOURCE SEGMENTS FOR THIS BATCH (ground truth for anything ")
              .append("flagged as missing/incorrect PARVW, POSEX, item code, price, or quantity):\n");
            sb.append(rawBatchSegmentsText).append("\n");
        }

        sb.append("\nPREVIOUS (DEFECTIVE) RESPONSE:\n");
        sb.append(previousResponseXml).append("\n");

        return sb.toString();
    }
}