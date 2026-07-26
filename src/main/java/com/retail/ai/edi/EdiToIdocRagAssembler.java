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

    // Tune this. Larger batches = fewer LLM calls but bigger prompts and
    // more chance the model loses track of correlation across many
    // LIN/PIA/IMD/QTY/PRI segments in one shot. 3-5 is a reasonable start.
    private static final int BATCH_SIZE = 5;

    // Delay between batches (not between every single segment anymore).
    // Set to 0 if your completionService has no rate limit concerns.
    private static final long DELAY_BETWEEN_BATCHES_MS = 2_000;

    // How many corrective re-prompts to allow per batch attempt before
    // giving up on THAT attempt (autoFix/validate retry loop within a
    // single batch call).
    private static final int MAX_CORRECTION_ATTEMPTS = 2;

    // How many times a discarded batch's segments may be REQUEUED as a
    // fresh, isolated batch of their own before being permanently dropped.
    // This is what prevents data loss when a batch fails validation and
    // gets discarded -- instead of losing those segments forever, they get
    // one (or more) more isolated shot(s), separate from whatever batch
    // they originally arrived in.
    private static final int MAX_REQUEUE_ATTEMPTS = 1;

    /**
     * Small wrapper so we can track how many times a given group of
     * segments has already been requeued after a discard, and stop
     * retrying indefinitely.
     */
    private static class BatchUnit {
        final List<EdiSegment> segments;
        final int requeueCount;

        BatchUnit(List<EdiSegment> segments, int requeueCount) {
            this.segments = segments;
            this.requeueCount = requeueCount;
        }
    }

    public AssemblyResult assemble(String ediXml, String tenant, String transactionTypeCode) {

        log.info("========== IDOC Assembly Started (Batched) ==========");
        log.info("Tenant: {}, TransactionType: {}", tenant, transactionTypeCode);

        EdiXmlParser parser = new EdiXmlParser();
        List<EdiSegment> segments = parser.parse(ediXml);
        // Ground-truth CREDAT, captured once from the raw segment stream
        // before any LLM involvement, so validate() can check the LLM's
        // output against it instead of trusting its judgment.
        String expectedCredat = extractExpectedCredat(segments);

        // Initial empty IDOC
        String currentIdocXml = """
                <ORDERS05>
                    <IDOC BEGIN="1">
                    </IDOC>
                </ORDERS05>
                """;

        // FIX #3a: batch by item group (cut before each new LIN) instead of
        // blind fixed-size chunking, so QTY/PRI segments belonging to one
        // LIN item never get split across a batch boundary from a
        // different item's LIN/QTY/PRI segments.
        List<List<EdiSegment>> initialBatches = partitionByItemGroup(segments, BATCH_SIZE);

        // FIX (data loss): use a work queue instead of a plain for-each, so
        // a discarded batch's segments can be pushed back onto the front of
        // the queue and retried as their own isolated batch, instead of
        // being silently dropped forever.
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
                System.out.println(String.format("-----> Segment XML :%n%s", segment.getRawXml()));
            }

            long retrievalStart = System.currentTimeMillis();
            // Fetch mapping chunks for every segment in the batch, then
            // de-dupe by chunk text so the same reference doesn't get
            // repeated in the prompt if multiple segments in the batch
            // hit the same chunk.
            LinkedHashMap<String, MappingChunk> chunkMap = new LinkedHashMap<>();
            for (EdiSegment segment : batch) {
                List<MappingChunk> chunksForSegment = mappingChunkProvider.fetchMappingChunk(
                        tenant,
                        transactionTypeCode,
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
            // Snapshot BEFORE this batch's call, so validate() can detect
            // if the LLM silently deletes previously-built content, and so
            // we have something safe to revert to if this batch fails.
            String idocBeforeBatch = currentIdocXml;
            
            List<String> expectedParvwCodesForBatch = extractParvwValues(idocBeforeBatch);
            for (EdiSegment segment : batch) {
                if ("NAD".equals(segment.getName())) {
                    String qualifier = extractFirstFieldQualifier(segment.getRawXml());
                    if (qualifier != null && !expectedParvwCodesForBatch.contains(qualifier)) {
                        expectedParvwCodesForBatch.add(qualifier);
                    }
                }
            }
            // Build prompt with previous IDOC + ALL segments in this batch
            String prompt = PromptHelper.buildPromptIDocMappingBatch(
                    batch,
                    mappingChunks,
                    currentIdocXml);

            String completionText = callLlmForBatch(prompt, batchNo);
            if (completionText == null) {
                // empty / unmapped -- callLlmForBatch already logged why
                batchNo++;
                continue;
            }
            // Deterministic, safe fixes first (no LLM involvement, can't
            // get these wrong): BEGIN attr, TABNAM, PARTN colon-strip,
            // CREDAT century-expand, non-numeric DOCNUM removal.
            completionText = IdocXmlValidator.autoFix(completionText);
            // Structural + semantic checks that DO require judgment to fix
            // (dropped E1EDP19 code, deleted prior content, wrong-source
            // CREDAT, reinterpreted PARVW) get a bounded number of
            // corrective re-prompts.
            IdocXmlValidator.ValidationResult validation = IdocXmlValidator.validate(completionText, idocBeforeBatch,
                    expectedCredat, expectedParvwCodesForBatch);
            // DIAGNOSTIC: log unconditionally (not just on failure) so we
            // can confirm validate() is actually being called with the
            // real idocBeforeBatch and actually returning what we expect,
            // rather than assuming it. Remove once confirmed stable.
            System.out.println("---->Batch " + batchNo + " validation.valid=" + validation.valid
                    + ", errors=" + validation.errors.size());
            System.out.println("---->idocBeforeBatch E1EDP01 POSEX values: "
                    + extractPosexValues(idocBeforeBatch));
            System.out.println("---->completionText E1EDP01 POSEX values: "
                    + extractPosexValues(completionText));
            System.out.println("---->expectedParvwCodesForBatch: " + expectedParvwCodesForBatch);

            int attempt = 0;
            while (!validation.valid && attempt < MAX_CORRECTION_ATTEMPTS) {
                attempt++;
                System.err.println("----> Batch " + batchNo + " failed validation (attempt "
                        + attempt + "/" + MAX_CORRECTION_ATTEMPTS + "):");
                validation.errors.forEach(e -> System.err.println("       - " + e));

                String correctivePrompt = IdocXmlValidator.buildCorrectivePrompt(completionText, validation.errors);

                String corrected = callLlmForBatch(correctivePrompt, batchNo);
                if (corrected == null) {
                    break; // empty/unmapped correction response -- stop retrying, keep last good text
                }

                corrected = IdocXmlValidator.autoFix(corrected);
                validation = IdocXmlValidator.validate(corrected, idocBeforeBatch,
                        expectedCredat, expectedParvwCodesForBatch);
                completionText = corrected;

                // DIAGNOSTIC: same unconditional log, for each retry.
                System.out.println("---->Batch " + batchNo + " retry " + attempt+ " validation.valid=" + validation.valid+ ", errors=" + validation.errors.size());
                System.out.println("---->idocBeforeBatch E1EDP01 POSEX values: "+ extractPosexValues(idocBeforeBatch));
                System.out.println("---->corrected E1EDP01 POSEX values: "+ extractPosexValues(completionText));
            }
            if (!validation.valid) {
                // FIX (data loss): instead of silently dropping this
                // batch's segments forever, requeue them as their own
                // isolated batch (up to MAX_REQUEUE_ATTEMPTS times) so
                // they get another chance -- now separated from whatever
                // else was originally batched alongside them, which often
                // is itself enough to let the LLM succeed on the retry.
                if (unit.requeueCount < MAX_REQUEUE_ATTEMPTS) {
                    System.err.println("----> Batch " + batchNo + " still has unresolved issues after "
                            + MAX_CORRECTION_ATTEMPTS + " correction attempts. REQUEUEING its "
                            + batch.size() + " segment(s) as an isolated retry batch (attempt "
                            + (unit.requeueCount + 1) + "/" + MAX_REQUEUE_ATTEMPTS + "):");
                    validation.errors.forEach(e -> System.err.println("       - " + e));

                    workQueue.addFirst(new BatchUnit(batch, unit.requeueCount + 1));
                    currentIdocXml = idocBeforeBatch;
                } else {
                    // Exhausted requeue attempts too -- this is now a real,
                    // permanent loss of this segment data from the final
                    // IDoc. Log it LOUDLY (not just to the same stream as
                    // routine validation retries) so it's not missed.
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

            // Update current IDOC
            currentIdocXml = completionText;
            System.out.println("*******************************************");
            System.out.println(currentIdocXml);
            System.out.println("*******************************************");
            System.err.println("--->Batch " + batchNo + " successfully merged into IDOC.");

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

        // TODO: Parse currentIdocXml into your AssemblyResult
        return null;
    }

    /**
     * Calls the LLM with the given prompt and returns the trimmed response
     * text, or null if the response was blank or "&lt;unmapped/&gt;" (in
     * which case the caller should skip/stop, and this method already
     * logs why).
     */
    private String callLlmForBatch(String prompt, int batchNo) {
        CompletionRequest request = new CompletionRequest();
        request.setPrompt(prompt);

        // DIAGNOSTIC: confirm the outgoing prompt actually differs per
        // batch and actually contains the real segment content, rather
        // than trusting that it does. Remove once confirmed.
        System.out.println("---->Prompt length for batch " + batchNo + ": " + prompt.length());
        System.out.println("---->Prompt hash for batch " + batchNo + ": " + prompt.hashCode());
        // NOTE: "CURRENT EDI SEGMENTS" also appears earlier in the prompt
        // inside the instructional text (in quotes), so indexOf() alone
        // grabs the wrong occurrence. Search for the actual section header
        // text instead, which is unique.
        String sectionMarker = "CURRENT EDI SEGMENTS (process ALL of these";
        int segStart = prompt.indexOf(sectionMarker);
        if (segStart >= 0) {
            int sectionEnd = prompt.indexOf("RETRIEVED MAPPING CHUNKS", segStart);
            int end = sectionEnd >= 0 ? sectionEnd : Math.min(prompt.length(), segStart + 1200);
            System.out.println("---->ACTUAL CURRENT EDI SEGMENTS section for batch " + batchNo
                    + ":\n" + prompt.substring(segStart, end));
        } else {
            // Expected/normal for corrective retry prompts built by
            // buildCorrectivePrompt() -- those are short and intentionally
            // don't repeat the full segment-list section. Only worth
            // investigating if this fires on the FIRST call of a batch
            // (the long ~20k+ char prompt), not on retries.
            System.out
                    .println("---->WARNING: real segment-list section header not found in prompt for batch " + batchNo);
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

        // Model frequently wraps its answer in ```xml ... ``` even though
        // the prompt says not to. Strip it here, once, so every downstream
        // consumer (autoFix, validate, merge into currentIdocXml) always
        // sees raw XML.
        completionText = IdocXmlValidator.stripCodeFences(completionText);

        return completionText;
    }

    /**
     * FIX #3a: Splits a list into consecutive sublists of at most
     * {@code maxSize} elements, but never splits a LIN item's related
     * segments (QTY/PRI/etc. following it) across a boundary from a
     * DIFFERENT item's LIN. A new batch always starts fresh at a LIN
     * segment boundary once the current batch has content, which prevents
     * the cross-item QTY/PRI correlation bug (item A's trailing QTY/PRI
     * getting batched together with item B's LIN/QTY/PRI and the LLM
     * mis-attributing values between them).
     */
    private static List<List<EdiSegment>> partitionByItemGroup(List<EdiSegment> list, int maxSize) {
        List<List<EdiSegment>> result = new ArrayList<>();
        List<EdiSegment> current = new ArrayList<>();
        for (EdiSegment seg : list) {
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
     * DIAGNOSTIC helper: extracts every POSEX value found in a given IDoc
     * XML string, purely for logging. Returns a bracketed list like
     * "[1, 2]" or "[]" if none found / xml doesn't parse.
     */
    private static List<String> extractPosexValues(String xml) {
        return extractChildTextValues(xml, "E1EDP01", "POSEX");
    }

    /**
     * Extracts every PARVW value currently present in a given IDoc XML
     * string. Used to seed the per-batch expected-PARVW set with whatever
     * has ALREADY been successfully merged, so the check only ever
     * requires (a) what's already there to persist, plus (b) what THIS
     * batch's own NAD segments introduce -- never qualifiers from batches
     * that never successfully merged.
     */
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

    /**
     * FIX #1: Finds the UNB segment in the full segment list and extracts
     * its date field (format "YYMMDD:HHMM", e.g. "200722:1500"),
     * century-expanding it to an 8-digit CREDAT (e.g. "20200722"). Returns
     * null if no UNB segment is found or its date field doesn't parse,
     * in which case the CREDAT validation check is simply skipped rather
     * than failing spuriously.
     *
     * NOTE: adjust the field index below (currently assuming the date:time
     * field is the 4th field, index 3, of UNB based on your sample --
     * "UNOC:3" / "SENDERID:ZZ" / "RECEIVERID:ZZ" / "200722:1500" / ... --
     * to match however EdiSegment actually exposes individual fields.
     */
    private static String extractExpectedCredat(List<EdiSegment> segments) {
        for (EdiSegment seg : segments) {
            if ("UNB".equals(seg.getName())) {
                List<String> fields = seg.getFields(); // adjust to your real accessor
                if (fields != null && fields.size() > 3) {
                    String dateTimeField = fields.get(3); // "200722:1500"
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

    /**
     * FIX #2: Extracts the qualifier code from a NAD segment's raw XML
     * (the first &lt;field&gt;, e.g. "SE" from
     * &lt;field&gt;SE&lt;/field&gt;&lt;field&gt;SUPPLIERID::92&lt;/field&gt;).
     * Returns null if it can't find/parse a qualifier. Adjust the parsing
     * here if EdiSegment exposes fields as a structured list instead of
     * raw XML -- prefer that over regex if available.
     */
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

    @Data
    @Builder
    public static class AssemblyResult {
        private final String finalXml;
        private final List<SegmentResult> segmentResults;
        private final List<String> unmappedSegments;
        private final boolean hasContent;
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
