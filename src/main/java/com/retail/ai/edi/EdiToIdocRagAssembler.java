package com.retail.ai.edi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.dto.EdiDataEvent;
import com.retail.ai.service.CompletionService;
import com.retail.ai.utilty.PromptHelper;

import lombok.Builder;
import lombok.Data;

public class EdiToIdocRagAssembler {

    private static final Logger log = LoggerFactory.getLogger(EdiToIdocRagAssembler.class);

    private final MappingChunkProvider mappingChunkProvider;
    private final CompletionService completionService;

    public EdiToIdocRagAssembler(MappingChunkProvider mappingChunkProvider, CompletionService completionService) {
        this.mappingChunkProvider = mappingChunkProvider;
        this.completionService = completionService;
    }

    private static final int BATCH_SIZE = 5;
    private static final long DELAY_BETWEEN_BATCHES_MS = 2_000;
    private static final int MAX_CORRECTION_ATTEMPTS = 2;
    private static final int MAX_REQUEUE_ATTEMPTS = 1;

    private static class BatchUnit {
        final List<EdiSegment> segments;
        final int requeueCount;

        BatchUnit(List<EdiSegment> segments, int requeueCount) {
            this.segments = segments;
            this.requeueCount = requeueCount;
        }
    }

    public AssemblyResult assemble(EdiDataEvent event) {
       
       // event.getPayload(),
                //    event.getTenant(),
                 //   event.getTransactionTypeCode()
       
        EdiToIdocRagAssembler.AssemblyResult assemblyResult =null;
        log.info("========== IDOC Assembly Started (Batched) ==========");
        log.info("Tenant: {}, TransactionType: {}", event.getTenant(), event.getTransactionTypeCode());

        EdiXmlParser parser = new EdiXmlParser();
        List<EdiSegment> segments = parser.parse(event.getPayload());
        String expectedCredat = extractExpectedCredat(segments);

        String currentIdocXml = """
                <ORDERS05>
                    <IDOC BEGIN="1">
                    </IDOC>
                </ORDERS05>
                """;

        List<List<EdiSegment>> initialBatches = partitionByItemGroup(segments, BATCH_SIZE);

        Deque<BatchUnit> workQueue = new ArrayDeque<>();
        for (List<EdiSegment> b : initialBatches) {
            workQueue.add(new BatchUnit(b, 0));
        }

        int batchNo = 1;
        int totalBatchesForLogging = initialBatches.size();

        while (!workQueue.isEmpty()) {
            BatchUnit unit = workQueue.poll();
            List<EdiSegment> batch = unit.segments;
            System.out.println("--------------------------********************************************------------------------------");
            System.out.println(String.format("---->Processing Batch %d/%d%s (%d segments)",
                    batchNo, totalBatchesForLogging,
                    unit.requeueCount > 0 ? " [requeue attempt " + unit.requeueCount + "]" : "",
                    batch.size()));

            for (EdiSegment segment : batch) {
                System.out.println(String.format("----> Segment Name : %s", segment.getName()));
            }

            long retrievalStart = System.currentTimeMillis();
            LinkedHashMap<String, MappingChunk> chunkMap = new LinkedHashMap<>();
            for (EdiSegment segment : batch) {
                List<MappingChunk> chunksForSegment = mappingChunkProvider.fetchMappingChunk(
                        event.getTenant(),
                        event.getTransactionTypeCode(),
                        segment.getName(),
                        segment.getRawXml());

                if (chunksForSegment != null) {
                    for (MappingChunk chunk : chunksForSegment) {
                        chunkMap.putIfAbsent(chunk.getChunkText(), chunk);
                    }
                }
            }

            long retrievalEnd = System.currentTimeMillis();
            System.out.println("---> Vector Search Time (batch) : " + (retrievalEnd - retrievalStart));
            List<MappingChunk> mappingChunks = new ArrayList<>(chunkMap.values());
            if (mappingChunks.isEmpty()) {
                System.out.println("---->No mapping chunks found for batch " + batchNo);
                batchNo++;
                continue;
            }

            String idocBeforeBatch = currentIdocXml;

            List<String> expectedParvwCodesForBatch = extractParvwValues(idocBeforeBatch);
            // FIX (new): the same kind of "known-good set so far" tracking
            // that already existed for PARVW, now added for POSEX. Seeded
            // from whatever POSEX values are already successfully merged,
            // plus any NEW literal LIN line numbers this batch introduces.
            // Anything the LLM outputs outside this set has no traceable
            // source LIN and is treated as fabricated.
            List<String> expectedPosexCodesForBatch = extractPosexValues(idocBeforeBatch);
            for (EdiSegment segment : batch) {
                if ("NAD".equals(segment.getName())) {
                    String qualifier = extractFirstFieldQualifier(segment.getRawXml());
                    if (qualifier != null && !expectedParvwCodesForBatch.contains(qualifier)) {
                        expectedParvwCodesForBatch.add(qualifier);
                    }
                }
                if ("LIN".equals(segment.getName())) {
                    for (String lineNumber : extractLinLineNumbers(segment.getRawXml())) {
                        if (!expectedPosexCodesForBatch.contains(lineNumber)) {
                            expectedPosexCodesForBatch.add(lineNumber);
                        }
                    }
                }
            }

            // Raw source text for this batch, in the SAME shape the initial
            // prompt shows it in, so a corrective retry can be handed this
            // verbatim instead of losing it after the first call.
            String rawBatchSegmentsText = buildRawBatchSegmentsText(batch);

            String prompt = PromptHelper.buildPromptIDocMappingBatch(
                    batch,
                    mappingChunks,
                    currentIdocXml);

            String completionText = callLlmForBatch(prompt, batchNo);
            if (completionText == null) {
                batchNo++;
                continue;
            }

            completionText = IdocXmlValidator.autoFix(completionText);
            IdocXmlValidator.ValidationResult validation = IdocXmlValidator.validate(completionText, idocBeforeBatch,
                    expectedCredat, expectedParvwCodesForBatch, expectedPosexCodesForBatch,
                    batch);

            System.out.println("---->Batch " + batchNo + " validation.valid=" + validation.valid
                    + ", errors=" + validation.errors.size());
            System.out.println("---->idocBeforeBatch E1EDP01 POSEX values: "
                    + extractPosexValues(idocBeforeBatch));
            System.out.println("---->completionText E1EDP01 POSEX values: "
                    + extractPosexValues(completionText));
            System.out.println("---->expectedParvwCodesForBatch: " + expectedParvwCodesForBatch);
            System.out.println("---->expectedPosexCodesForBatch: " + expectedPosexCodesForBatch);

            int attempt = 0;
            while (!validation.valid && attempt < MAX_CORRECTION_ATTEMPTS) {
                attempt++;
                System.err.println("----> Batch " + batchNo + " failed validation (attempt "
                        + attempt + "/" + MAX_CORRECTION_ATTEMPTS + "):");
                validation.errors.forEach(e -> System.err.println("       - " + e));

                // FIX (new): pass idocBeforeBatch + rawBatchSegmentsText so
                // the retry has real ground truth to recover values from,
                // instead of only the defective draft + error descriptions.
                String correctivePrompt = IdocXmlValidator.buildCorrectivePrompt(
                        completionText, validation.errors, idocBeforeBatch, rawBatchSegmentsText);

                String corrected = callLlmForBatch(correctivePrompt, batchNo);
                if (corrected == null) {
                    break;
                }

                corrected = IdocXmlValidator.autoFix(corrected);
                validation = IdocXmlValidator.validate(corrected, idocBeforeBatch,
                        expectedCredat, expectedParvwCodesForBatch, expectedPosexCodesForBatch,
                        batch);
                completionText = corrected;

                System.out.println("---->Batch " + batchNo + " retry " + attempt+ " validation.valid=" + validation.valid+ ", errors=" + validation.errors.size());
                System.out.println("---->idocBeforeBatch E1EDP01 POSEX values: "+ extractPosexValues(idocBeforeBatch));
                System.out.println("---->corrected E1EDP01 POSEX values: "+ extractPosexValues(completionText));
            }
            if (!validation.valid) {
                if (unit.requeueCount < MAX_REQUEUE_ATTEMPTS) {
                    System.err.println("----> Batch " + batchNo + " still has unresolved issues after "
                            + MAX_CORRECTION_ATTEMPTS + " correction attempts. REQUEUEING its "
                            + batch.size() + " segment(s) as an isolated retry batch (attempt "
                            + (unit.requeueCount + 1) + "/" + MAX_REQUEUE_ATTEMPTS + "):");
                    validation.errors.forEach(e -> System.err.println("       - " + e));

                    workQueue.addFirst(new BatchUnit(batch, unit.requeueCount + 1));
                    currentIdocXml = idocBeforeBatch;
                } else {
                    System.err.println("----> Batch " + batchNo + " PERMANENTLY DROPPED after "
                            + MAX_CORRECTION_ATTEMPTS + " correction attempts and "
                            + MAX_REQUEUE_ATTEMPTS + " requeue attempt(s). The following "
                            + batch.size() + " source segment(s) could NOT be mapped and are "
                            + "MISSING from the final IDoc -- this requires manual review:");
                    for (EdiSegment segment : batch) {
                        System.err.println("       - " + segment.getName() + ": " + segment.getRawXml());
                    }
                    validation.errors.forEach(e -> System.err.println("       - " + e));
                    currentIdocXml = idocBeforeBatch;
                }

                if (DELAY_BETWEEN_BATCHES_MS > 0) {
                    try {
                        System.out.println("Waiting " + DELAY_BETWEEN_BATCHES_MS + " ms before next batch...");
                        Thread.sleep(DELAY_BETWEEN_BATCHES_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("Thread interrupted while waiting.");
                        break;
                    }
                }
                batchNo++;
                continue;
            }

            currentIdocXml = completionText;
            System.out.println("*******************************************");
            System.out.println("--lengt---"+currentIdocXml.length());
            System.out.println(currentIdocXml);
            System.out.println("*******************************************");
            System.err.println("--->Batch " + batchNo + " successfully merged into IDOC.");

            // FIX (new): non-blocking diagnostic -- validation guarantees the
            // merge never SHRINKS or MUTATES prior content, but it doesn't by
            // itself prove this batch's own segments actually landed
            // anywhere. If this batch contained content-bearing segments
            // (NAD/LIN/PIA/IMD/QTY/PRI/CUX) but the total element count did
            // not increase, that's worth a loud log line rather than silent
            // assumption of success.
            boolean batchHadContentSegments = batch.stream().anyMatch(s ->
                    List.of("NAD", "LIN", "PIA", "IMD", "QTY", "PRI", "CUX").contains(s.getName()));
            int beforeCount = countElements(idocBeforeBatch);
            int afterCount = countElements(currentIdocXml);
            if (batchHadContentSegments && afterCount <= beforeCount) {
                log.warn("----> WARNING: Batch {} passed validation but the merged IDoc's element count did not increase ({} -> {}) despite containing content-bearing segments. Verify this batch's data actually landed in the output.",
                        batchNo, beforeCount, afterCount);
            } else {
                log.info("---->Batch {} element count: {} -> {}", batchNo, beforeCount, afterCount);
            }

            if (DELAY_BETWEEN_BATCHES_MS > 0) {
                try {
                    System.out.println("Waiting " + DELAY_BETWEEN_BATCHES_MS + " ms before next batch...");
                    Thread.sleep(DELAY_BETWEEN_BATCHES_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Thread interrupted while waiting.");
                    break;
                }
            }

            batchNo++;
        }

        System.out.println("========== IDOC Assembly Completed ==========");
        System.out.println("------>Final IDOC Length : " + currentIdocXml.length());
        System.err.println("---------> Final IDOC : ");
        System.err.println(currentIdocXml);

        assemblyResult =
        new EdiToIdocRagAssembler.AssemblyResult(currentIdocXml);

        return assemblyResult;
    }

    private String callLlmForBatch(String prompt, int batchNo) {
        CompletionRequest request = new CompletionRequest();
        request.setPrompt(prompt);

        System.out.println("---->Prompt length for batch " + batchNo + ": " + prompt.length());
        System.out.println("---->Prompt hash for batch " + batchNo + ": " + prompt.hashCode());
        String sectionMarker = "CURRENT EDI SEGMENTS (process ALL of these";
        int segStart = prompt.indexOf(sectionMarker);
        if (segStart >= 0) {
            int sectionEnd = prompt.indexOf("RETRIEVED MAPPING CHUNKS", segStart);
            int end = sectionEnd >= 0 ? sectionEnd : Math.min(prompt.length(), segStart + 1200);
            System.out.println("---->ACTUAL CURRENT EDI SEGMENTS section for batch " + batchNo
                    + ":\n" + prompt.substring(segStart, end));
        } else {
            System.out
                    .println("---->NOTE: real segment-list section header not found in prompt for batch " + batchNo
                            + " -- expected for corrective retries, which now embed the source "
                            + "segments under a different header (\"ORIGINAL SOURCE SEGMENTS FOR "
                            + "THIS BATCH\") instead of duplicating this exact one.");
        }

        System.out.println("---->Calling LLM for batch " + batchNo + "...");

        long llmStart = System.currentTimeMillis();

        CompletionResponse response = completionService.generateCompletion(request);

        long llmEnd = System.currentTimeMillis();

        System.out.println("--->LLM Response Time :" + (llmEnd - llmStart) + " ms");

        String completionText = response != null && response.getText() != null
                ? response.getText().trim()
                : "";

        System.out.println("---> LLM Response Length : " + completionText.length());
        System.out.println("--->LLM Generated XML: " + completionText);

        if (completionText.isBlank()) {
            System.err.println("----> Batch " + batchNo + " returned empty response.");
            return null;
        }

        if ("<unmapped/>".equalsIgnoreCase(completionText)) {
            System.err.println("----> Batch " + batchNo + " could not be mapped.");
            return null;
        }

        completionText = IdocXmlValidator.stripCodeFences(completionText);

        return completionText;
    }

    private static List<List<EdiSegment>> partitionByItemGroup(List<EdiSegment> list, int maxSize) {
        List<List<EdiSegment>> result = new ArrayList<>();
        List<EdiSegment> current = new ArrayList<>();
        for (EdiSegment seg : list) {

            //System.out.println(seg.getName()+"==========seg======"+seg.getRawXml());

            boolean startsNewItem = "LIN".equals(seg.getName());
            if (startsNewItem && !current.isEmpty()) {
                result.add(current);
                current = new ArrayList<>();
            }
            current.add(seg);
            if (current.size() >= maxSize) {
                result.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    /**
     * FIX (new): total element count in the given IDoc XML, used purely as
     * a coarse "did this batch actually add anything" diagnostic alongside
     * the strict content-level checks in IdocXmlValidator. Returns -1 if
     * the XML doesn't parse (shouldn't happen for anything that already
     * passed validation, but this is a diagnostic, not a gate -- never
     * throw from here).
     */
    private static int countElements(String xml) {
        if (xml == null || xml.isBlank()) {
            return 0;
        }
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(
                            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return doc.getElementsByTagName("*").getLength();
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<String> extractPosexValues(String xml) {
        return extractChildTextValues(xml, "E1EDP01", "POSEX");
    }

    private static List<String> extractParvwValues(String xml) {
        return extractChildTextValues(xml, "E1EDKA1", "PARVW");
    }

    private static List<String> extractChildTextValues(String xml, String parentTag, String childTag) {
        List<String> result = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        try {
            javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(
                            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.w3c.dom.NodeList parents = doc.getElementsByTagName(parentTag);
            for (int i = 0; i < parents.getLength(); i++) {
                org.w3c.dom.Element el = (org.w3c.dom.Element) parents.item(i);
                org.w3c.dom.NodeList children = el.getElementsByTagName(childTag);
                String value = children.getLength() > 0
                        ? children.item(0).getTextContent().trim()
                        : "?";
                if (!result.contains(value)) {
                    result.add(value);
                }
            }
        } catch (Exception e) {
            result.add("<parse-error: " + e.getMessage() + ">");
        }
        return result;
    }

    private static String extractExpectedCredat(List<EdiSegment> segments) {
        for (EdiSegment seg : segments) {
            if ("UNB".equals(seg.getName())) {
                List<String> fields = seg.getFields();
                if (fields != null && fields.size() > 3) {
                    String dateTimeField = fields.get(3);
                    int colonIdx = dateTimeField.indexOf(':');
                    String datePart = colonIdx >= 0 ? dateTimeField.substring(0, colonIdx) : dateTimeField;
                    if (datePart.matches("\\d{6}")) {
                        return "20" + datePart;
                    }
                }
                return null;
            }
        }
        return null;
    }

    private static String extractFirstFieldQualifier(String rawSegmentXml) {
        int start = rawSegmentXml.indexOf("<field>");
        if (start < 0)
            return null;
        start += "<field>".length();
        int end = rawSegmentXml.indexOf("</field>", start);
        if (end < 0)
            return null;
        String value = rawSegmentXml.substring(start, end).trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * FIX (new): extracts the literal line-number(s) from a LIN segment's
     * raw XML. Handles both shapes documented in PromptHelper: a single
     * "line_number, code:qualifier" pair, or multiple pairs concatenated
     * as repeating (line_number, code:qualifier) fields -- in which case
     * every ODD-indexed field (0-based: 0, 2, 4, ...) is a line number.
     */
    private static List<String> extractLinLineNumbers(String rawSegmentXml) {
        List<String> lineNumbers = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<field>(.*?)</field>")
                .matcher(rawSegmentXml);
        while (m.find()) {
            fields.add(m.group(1).trim());
        }
        // fields = [lineNo1, code:qual1, lineNo2, code:qual2, ...]
        for (int i = 0; i < fields.size(); i += 2) {
            String candidate = fields.get(i);
            if (candidate.matches("\\d+")) {
                lineNumbers.add(candidate);
            }
        }
        return lineNumbers;
    }

    /**
     * FIX (new): re-renders this batch's segments in the same
     * "Segment N of M -- name: X" shape the initial prompt used, so it can
     * be embedded verbatim into a corrective retry prompt as ground truth.
     */
    private static String buildRawBatchSegmentsText(List<EdiSegment> batch) {
        StringBuilder sb = new StringBuilder();
        int segNo = 1;
        for (EdiSegment segment : batch) {
            sb.append("Segment ").append(segNo++).append(" of ").append(batch.size())
              .append(" -- name: ").append(segment.getName()).append("\n");
            sb.append(segment.getRawXml()).append("\n\n");
        }
        return sb.toString();
    }

    @Data
    @Builder
    public static class AssemblyResult {
        private final String finalXml;
        
    }

    @Data
    public static class SegmentResult {
        private final String segmentName;
        private final String status;
        private final String fragment;

        public static SegmentResult success(String segmentName, String fragment) {
            return new SegmentResult(segmentName, "success", fragment);
        }

        public static SegmentResult failure(String segmentName, String reason) {
            return new SegmentResult(segmentName, "failed:" + reason, null);
        }

        public static SegmentResult skipped(String segmentName) {
            return new SegmentResult(segmentName, "skipped", null);
        }
    }
}