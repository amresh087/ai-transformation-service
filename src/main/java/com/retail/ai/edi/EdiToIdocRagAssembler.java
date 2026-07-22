package com.retail.ai.edi;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.service.CompletionService;
import lombok.Data;
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

public class EdiToIdocRagAssembler {

    private final SegmentHierarchyRuleResolver resolver;
    private final MappingChunkProvider mappingChunkProvider;
    private final CompletionService completionService;

    public EdiToIdocRagAssembler(SegmentHierarchyRuleResolver resolver,
                                  MappingChunkProvider mappingChunkProvider,
                                  CompletionService completionService) {
        this.resolver = resolver;
        this.mappingChunkProvider = mappingChunkProvider;
        this.completionService = completionService;
    }

    public AssemblyResult assemble(String ediXml, String tenant, String transactionTypeCode) {
        EdiXmlParser parser = new EdiXmlParser();
        List<EdiSegment> segments = parser.parse(ediXml);

        List<String> headerFragments = new ArrayList<>();
        List<String> lineItemFragments = new ArrayList<>();
        List<String> unmappedSegments = new ArrayList<>();
        List<SegmentResult> segmentResults = new ArrayList<>();

        List<String> currentLineItemFragments = new ArrayList<>();
        int lineItemIndex = 0;

        for (EdiSegment segment : segments) {
            SegmentHierarchyRole role = resolver.roleFor(segment.getName(), segment.getRawXml());
            String mappingChunk = mappingChunkProvider.fetchMappingChunk(tenant, transactionTypeCode, segment.getName(), segment.getRawXml());
            if (mappingChunk == null || mappingChunk.isBlank()) {
                unmappedSegments.add(segment.getName());
                segmentResults.add(SegmentResult.failure(segment.getName(), "No mapping chunk"));
                continue;
            }

            CompletionRequest request = CompletionRequest.builder()
                    .prompt(buildPrompt(mappingChunk, segment.getRawXml()))
                    .tenant(tenant)
                    .transactionTypeCode(transactionTypeCode)
                    .segmentName(segment.getName())
                    .build();
            CompletionResponse completionResponse = completionService.generateCompletion(request);
            String fragment = completionResponse != null && completionResponse.getText() != null ? completionResponse.getText() : "";
            String validatedFragment = validateAndNormalize(fragment);
            if (validatedFragment == null) {
                unmappedSegments.add(segment.getName());
                segmentResults.add(SegmentResult.failure(segment.getName(), "Invalid XML"));
                continue;
            }

            if (role == SegmentHierarchyRole.HEADER) {
                headerFragments.add(validatedFragment);
                segmentResults.add(SegmentResult.success(segment.getName(), validatedFragment));
            } else if (role == SegmentHierarchyRole.STARTS_LINE_ITEM) {
                if (!currentLineItemFragments.isEmpty()) {
                    lineItemFragments.add(buildLineItemWrapper(lineItemIndex, currentLineItemFragments));
                }
                lineItemIndex++;
                currentLineItemFragments = new ArrayList<>();
                currentLineItemFragments.add(validatedFragment);
                segmentResults.add(SegmentResult.success(segment.getName(), validatedFragment));
            } else if (role == SegmentHierarchyRole.BELONGS_TO_LINE_ITEM) {
                currentLineItemFragments.add(validatedFragment);
                segmentResults.add(SegmentResult.success(segment.getName(), validatedFragment));
            } else if (role == SegmentHierarchyRole.TRAILING || role == SegmentHierarchyRole.IGNORE) {
                segmentResults.add(SegmentResult.skipped(segment.getName()));
            }
        }

        if (!currentLineItemFragments.isEmpty()) {
            lineItemFragments.add(buildLineItemWrapper(lineItemIndex, currentLineItemFragments));
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<ORDERS05><IDOC>");
        for (String headerFragment : headerFragments) {
            xml.append(headerFragment);
        }
        for (String lineItemFragment : lineItemFragments) {
            xml.append(lineItemFragment);
        }
        xml.append("</IDOC></ORDERS05>");

        return new AssemblyResult(xml.toString(), segmentResults, unmappedSegments);
    }

    private String buildPrompt(String mappingChunk, String segmentXml) {
        return "Use the following XSLT mapping chunk to generate an IDOC XML fragment for this EDI segment.\n"
                + "Mapping chunk:\n" + mappingChunk + "\n\nSegment:\n" + segmentXml;
    }

    private String validateAndNormalize(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return null;
        }
        String cleaned = fragment.trim();
        if (!cleaned.startsWith("<")) {
            cleaned = "<fragment>" + cleaned + "</fragment>";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader("<root>" + cleaned + "</root>")));
            return extractInnerXml(document.getDocumentElement());
        } catch (Exception ex) {
            return null;
        }
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

    private String buildLineItemWrapperFragment(int lineItemIndex, String validatedFragment) {
        String posex = String.format("%05d", lineItemIndex);
        return "<E1EDP01><POSEX>" + posex + "</POSEX>" + validatedFragment + "</E1EDP01>";
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
