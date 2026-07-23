package com.retail.ai.edi;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.service.CompletionService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
        EdiXmlParser parser = new EdiXmlParser();
        List<EdiSegment> segments = parser.parse(ediXml);

        List<String> headerFragments = new ArrayList<>();
        List<String> lineItemFragments = new ArrayList<>();
        List<String> unmappedSegments = new ArrayList<>();
        List<SegmentResult> segmentResults = new ArrayList<>();

        DeterministicIdocHeaderBuilder.HeaderBuildResult headerBuildResult = headerBuilder.buildHeaderFragments(segments);
        headerFragments.addAll(headerBuildResult.getFragments());
        Set<Integer> headerHandledIndexes = headerBuildResult.getHandledSegmentIndices();

        List<String> currentLineItemFragments = new ArrayList<>();
        int lineItemIndex = 10;
        boolean hasContent = !headerFragments.isEmpty();

        for (EdiSegment segment : segments) {
            if (headerHandledIndexes.contains(segment.getSequenceIndex())) {
                segmentResults.add(SegmentResult.skipped(segment.getName()));
                continue;
            }
            SegmentHierarchyRole role = resolver.roleFor(segment.getName(), segment.getRawXml());
            if (role == SegmentHierarchyRole.STARTS_LINE_ITEM && !currentLineItemFragments.isEmpty()) {
                lineItemFragments.add(buildLineItemWrapper(lineItemIndex, currentLineItemFragments));
                currentLineItemFragments.clear();
                lineItemIndex += 10;
            }
            String fragment = tryDeterministicMapping(segment);
            if (fragment != null) {
                segmentResults.add(SegmentResult.success(segment.getName(), fragment));
                hasContent = appendFragment(role, fragment, currentLineItemFragments, headerFragments, lineItemFragments, lineItemIndex) || hasContent;
                continue;
            }

            MappingChunkResult chunkResult = mappingChunkProvider.fetchMappingChunk(tenant, transactionTypeCode, segment.getName(), segment.getRawXml());
            if (chunkResult == null || chunkResult.isEmpty()) {
                unmappedSegments.add(segment.getName());
                segmentResults.add(SegmentResult.failure(segment.getName(), "No mapping chunk"));
                continue;
            }

            if (chunkResult.getScore() < chunkResult.getThreshold()) {
                log.warn("Rejecting mapping chunk for segment {} because score {} is below threshold {}", segment.getName(), chunkResult.getScore(), chunkResult.getThreshold());
                unmappedSegments.add(segment.getName());
                segmentResults.add(SegmentResult.failure(segment.getName(), "Low similarity score"));
                continue;
            }

            if (!chunkContainsSegmentName(chunkResult.getChunkText(), segment.getName())) {
                log.warn("Mapping chunk for segment {} did not explicitly mention the segment name. Candidate chunk may be wrong. Chunk text starts: {}", segment.getName(), chunkResult.getChunkText() == null ? "<empty>" : chunkResult.getChunkText().replaceAll("\n", " ").strip());
            }

            CompletionRequest request = CompletionRequest.builder()
                    .prompt(buildPrompt(chunkResult.getChunkText(), segment.getRawXml()))
                    .tenant(tenant)
                    .transactionTypeCode(transactionTypeCode)
                    .segmentName(segment.getName())
                    .build();
            CompletionResponse completionResponse = completionService.generateCompletion(request);
            String completionText = completionResponse != null && completionResponse.getText() != null ? completionResponse.getText() : "";
            log.info("Raw LLM completion for segment {}: [{}]", segment.getName(), completionText);

            String validatedFragment = validateAndNormalize(completionText);
            if (validatedFragment == null) {
                unmappedSegments.add(segment.getName());
                segmentResults.add(SegmentResult.failure(segment.getName(), "Invalid or empty XML"));
                continue;
            }

            segmentResults.add(SegmentResult.success(segment.getName(), validatedFragment));
            hasContent = appendFragment(role, validatedFragment, currentLineItemFragments, headerFragments, lineItemFragments, lineItemIndex);
        }

        if (!currentLineItemFragments.isEmpty()) {
            lineItemFragments.add(buildLineItemWrapper(lineItemIndex, currentLineItemFragments));
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.append("<ORDERS05><IDOC BEGIN=\"1\">");
        for (String headerFragment : headerFragments) {
            xml.append(headerFragment);
        }
        for (String lineItemFragment : lineItemFragments) {
            xml.append(lineItemFragment);
        }
        xml.append("</IDOC></ORDERS05>");

        return new AssemblyResult(xml.toString(), segmentResults, unmappedSegments, hasContent);
    }

    private String tryDeterministicMapping(EdiSegment segment) {
        try {
            String deterministic = deterministicMapper.map(segment);
            if (deterministic == null || deterministic.isBlank()) {
                return null;
            }
            String normalized = validateAndNormalize(deterministic);
            if (normalized == null) {
                log.warn("Deterministic mapper produced invalid XML for segment {}: {}", segment.getName(), deterministic);
                return null;
            }
            log.info("Deterministic mapping used for segment {} (no LLM call)", segment.getName());
            return normalized;
        } catch (Exception ex) {
            log.warn("Deterministic mapping failed for segment {}", segment.getName(), ex);
            return null;
        }
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

    private String buildPrompt(String mappingChunk, String segmentXml) {
        return "Use the following XSLT mapping chunk to generate only the IDOC XML fragment for this EDI segment. "
                + "Do not echo the mapping chunk, do not return the XSLT mapping chunk, do not return any XML declaration, "
                + "and do not wrap the result in any additional documents or markdown fences. "
                + "Return only valid XML element content suitable for insertion inside an ORDERS05/IDOC document. "
                + "If you are unsure, return <unmapped/>.\n\n"
                + "Mapping chunk:\n" + mappingChunk + "\n\n"
                + "EDI segment XML:\n" + segmentXml;
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
