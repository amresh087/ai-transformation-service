package com.retail.ai.edi;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.service.CompletionService;
import com.retail.ai.utilty.PromptHelper;

import lombok.Builder;
import lombok.Data;

public class EdiToIdocRagAssembler {

    private static final Logger log = LoggerFactory.getLogger(EdiToIdocRagAssembler.class);

    private final SegmentHierarchyRuleResolver resolver;
    private final MappingChunkProvider mappingChunkProvider;
    private final CompletionService completionService;
    private final DeterministicEdiToIdocMapper deterministicMapper;
    private final DeterministicIdocHeaderBuilder headerBuilder;

    public EdiToIdocRagAssembler(SegmentHierarchyRuleResolver resolver,
                                  MappingChunkProvider mappingChunkProvider,
                                  CompletionService completionService,
                                  DeterministicEdiToIdocMapper deterministicMapper) {
        this.resolver = resolver;
        this.mappingChunkProvider = mappingChunkProvider;
        this.completionService = completionService;
        this.deterministicMapper = deterministicMapper;
        this.headerBuilder = new DeterministicIdocHeaderBuilder();
    }

    

public AssemblyResult assemble(String ediXml, String tenant, String transactionTypeCode) {

    log.info("========== IDOC Assembly Started ==========");
    log.info("Tenant: {}, TransactionType: {}", tenant, transactionTypeCode);

    EdiXmlParser parser = new EdiXmlParser();
    List<EdiSegment> segments = parser.parse(ediXml);

    log.info("Total EDI Segments Parsed: {}", segments.size());

    // Initial empty IDOC
    String currentIdocXml = """
            <ORDERS05>
                <IDOC BEGIN="1">
                </IDOC>
            </ORDERS05>
            """;

    int segmentNo = 1;

    for (EdiSegment segment : segments) {

        System.out.println("--------------------------********************************************------------------------------");

        System.out.println(String.format("---->Processing Segment %d/%d", segmentNo++, segments.size()));
        System.out.println(String.format("----> Segment Name : %s", segment.getName()));
        System.out.println(String.format("-----> Segment XML :%n%s", segment.getRawXml()));

        long retrievalStart = System.currentTimeMillis();

        List<MappingChunk> mappingChunks =
                mappingChunkProvider.fetchMappingChunk(
                        tenant,
                        transactionTypeCode,
                        segment.getName(),
                        segment.getRawXml());

        long retrievalEnd = System.currentTimeMillis();

        System.out.println("---> Vector Search Time : " + (retrievalEnd - retrievalStart));

        if (mappingChunks == null || mappingChunks.isEmpty()) {
            System.out.println("---->No mapping chunks found for segment " + segment.getName());
            continue;
        }

        // Build prompt with previous IDOC
        String prompt = PromptHelper.buildPromptIDocMapping(
                segment,
                mappingChunks,
                currentIdocXml);

        // System.out.println("---->Generated Prompt: " + prompt);

        CompletionRequest request = new CompletionRequest();
        request.setPrompt(prompt);

        System.out.println("---->Calling LLM...");

        long llmStart = System.currentTimeMillis();

        CompletionResponse response = completionService.generateCompletion(request);

        long llmEnd = System.currentTimeMillis();

        System.out.println("--->LLM Response Time :" + (llmEnd - llmStart) + " ms");

        String completionText =
                response != null && response.getText() != null
                        ? response.getText().trim()
                        : "";

        System.out.println("---> LLM Response Length : " + completionText.length());
        System.out.println("--->LLM Generated XML: " + completionText);

        if (completionText.isBlank()) {
            System.err.println("----> Segment " + segment.getName() + " returned empty response.");
            continue;
        }

        if ("<unmapped/>".equalsIgnoreCase(completionText)) {
            System.err.println("----> Segment " + segment.getName() + " could not be mapped.");
            continue;
        }

        /*
        
        // Validate response
        if (!completionText.startsWith("<ORDERS05")) {
            System.err.println("----> Invalid IDOC returned by LLM for segment " + segment.getName());
            continue;
        }
    */

        // Update current IDOC
        currentIdocXml = completionText;

        System.out.println("*******************************************");
        System.out.println(currentIdocXml);
        System.out.println("*******************************************");

        System.err.println("--->Segment " + segment.getName() + " successfully merged into IDOC.");

        try {
            System.out.println("Waiting 5 seconds before next LLM call...");
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Thread interrupted while waiting.");
            break;
        }
    }

    System.out.println("========== IDOC Assembly Completed ==========");
    System.out.println("------>Final IDOC Length : " + currentIdocXml.length());
    System.err.println("---------> Final IDOC : ");
    System.err.println(currentIdocXml);

    // TODO: Parse currentIdocXml into your AssemblyResult
    return null;
}

    private boolean appendFragment(SegmentHierarchyRole role,
                                   String fragment,
                                   List<String> currentLineItemFragments,
                                   List<String> headerFragments,
                                   List<String> lineItemFragments,
                                   int lineItemIndex) {
        if (role == SegmentHierarchyRole.HEADER) {
            headerFragments.add(fragment);
            return true;
        }
        if (role == SegmentHierarchyRole.STARTS_LINE_ITEM) {
            if (!currentLineItemFragments.isEmpty()) {
                lineItemFragments.add(buildLineItemWrapper(lineItemIndex, currentLineItemFragments));
                currentLineItemFragments.clear();
            }
            currentLineItemFragments.add(fragment);
            return true;
        }
        if (role == SegmentHierarchyRole.BELONGS_TO_LINE_ITEM) {
            currentLineItemFragments.add(fragment);
            return true;
        }
        return false;
    }

    private boolean chunkContainsSegmentName(String chunk, String segmentName) {
        if (chunk == null || segmentName == null) {
            return false;
        }
        String normalized = chunk.toUpperCase();
        String candidate = segmentName.toUpperCase();
        return normalized.contains(candidate);
    }

    


    

    private String validateAndNormalize(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return null;
        }

        String cleaned = stripMarkdownFences(fragment.trim());
        cleaned = stripXmlDeclaration(cleaned).trim();
        if (cleaned.isBlank()) {
            return null;
        }

        if (looksLikeStylesheetEcho(cleaned)) {
            log.warn("LLM output looks like XSLT mapping content instead of IDOC fragment. Output was: {}", cleaned);
            return null;
        }

        String wrapped = cleaned;
        if (!wrapped.startsWith("<")) {
            wrapped = "<fragment>" + wrapped + "</fragment>";
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader("<root>" + wrapped + "</root>")));
            return extractInnerXml(document.getDocumentElement());
        } catch (Exception ex) {
            log.warn("Failed to validate/normalize generated fragment. Raw output: {}", cleaned, ex);
            return null;
        }
    }

    private String stripXmlDeclaration(String text) {
        return text.replaceFirst("(?i)^\\s*<\\?xml[^>]*>\\s*", "");
    }

    private String stripMarkdownFences(String text) {
        return text.replaceAll("(?m)^(?:```|~~~).*$(?:\\R)?", "").trim();
    }

    private boolean looksLikeStylesheetEcho(String text) {
        String lower = text.toLowerCase();
        return lower.contains("<xsl:stylesheet")
                || lower.contains("<xsl:template")
                || lower.contains("<xsl:when")
                || lower.contains("<xsl:text")
                || lower.contains("<xsl:value-of")
                || lower.contains("<xsl:apply-templates")
                || lower.contains("<fragment")
                || lower.contains("<field>")
                || lower.contains("<orders05")
                || lower.contains("<idoc")
                || text.matches("(?s).*<\\?xml[^>]*>.*")
                || text.matches("(?s).*\\$[A-Za-z0-9_]+.*");
    }

    private String extractInnerXml(Element root) throws Exception {
        StringWriter writer = new StringWriter();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(new DOMSource(root), new StreamResult(writer));
        String xml = writer.toString();
        int start = xml.indexOf('>');
        int end = xml.lastIndexOf('<');
        if (start >= 0 && end > start) {
            return xml.substring(start + 1, end);
        }
        return xml;
    }

    private String buildLineItemWrapper(int lineItemIndex, List<String> fragments) {
        StringBuilder xml = new StringBuilder();
        xml.append("<E1EDP01><POSEX>").append(String.format("%05d", lineItemIndex)).append("</POSEX>");
        for (String fragment : fragments) {
            xml.append(fragment);
        }
        xml.append("</E1EDP01>");
        return xml.toString();
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
