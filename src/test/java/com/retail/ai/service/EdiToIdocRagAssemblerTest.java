package com.retail.ai.service;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.edi.EdiToIdocRagAssembler;
import com.retail.ai.edi.MappingChunkProvider;
import com.retail.ai.edi.MappingChunkResult;
import com.retail.ai.edi.SegmentHierarchyRuleResolver;
import com.retail.ai.service.CompletionService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdiToIdocRagAssemblerTest {

    @Test
    void shouldAssembleTwentyLineItemsAndLeaveNoUnmappedSegments() {
        SegmentHierarchyRuleResolver resolver = new SegmentHierarchyRuleResolver("default", "Levi's", "850");
        MappingChunkProvider mappingChunkProvider = (tenant, transactionTypeCode, segmentName, rawSegment) -> MappingChunkResult.of("mapping for " + segmentName, 0.95, 0.60);
        CompletionService completionService = new CompletionService() {
            @Override
            public CompletionResponse generateCompletion(CompletionRequest request) {
                return CompletionResponse.builder().text("<fragment><MENGE>10</MENGE></fragment>").build();
            }
        };

        EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(resolver, mappingChunkProvider, completionService, new com.retail.ai.edi.DeterministicEdiToIdocMapper());

        String ediXml = "<edi>" + buildSampleSegments(20) + "</edi>";
        EdiToIdocRagAssembler.AssemblyResult result = assembler.assemble(ediXml, "Levi's", "850");

        assertTrue(result.getFinalXml().contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"));
        assertTrue(result.getFinalXml().contains("<ORDERS05><IDOC BEGIN=\"1\">"));
        assertEquals(0, result.getUnmappedSegments().size());
        assertEquals(20, countOccurrences(result.getFinalXml(), "<E1EDP01"));
        assertTrue(result.getFinalXml().contains("<POSEX>00001</POSEX>"));
    }

    private String buildSampleSegments(int lineItemCount) {
        StringBuilder builder = new StringBuilder();
        builder.append("<segment name=\"UNB\"><field>UNOA:3</field></segment>");
        builder.append("<segment name=\"UNH\"><field>1</field></segment>");
        builder.append("<segment name=\"BGM\"><field>220</field></segment>");
        builder.append("<segment name=\"DTM\"><field>137:20260717:102</field></segment>");
        builder.append("<segment name=\"NAD\"><field>BY</field></segment>");
        builder.append("<segment name=\"RFF\"><field>ON:PO12345</field></segment>");
        for (int i = 1; i <= lineItemCount; i++) {
            builder.append("<segment name=\"LIN\"><field>").append(i).append("</field></segment>");
            builder.append("<segment name=\"QTY\"><field>21:").append(i).append("</field></segment>");
            builder.append("<segment name=\"PRI\"><field>AAA:").append(100 + i).append("</field></segment>");
        }
        builder.append("<segment name=\"UNT\"><field>999</field></segment>");
        builder.append("<segment name=\"UNZ\"><field>1</field></segment>");
        return builder.toString();
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = value.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }

    @Test
    void shouldEmbedDeterministicHeaderAndNumberLineItemsByTens() {
        SegmentHierarchyRuleResolver resolver = new SegmentHierarchyRuleResolver("default", "Levi's", "850");
        MappingChunkProvider mappingChunkProvider = (tenant, transactionTypeCode, segmentName, rawSegment) -> MappingChunkResult.of("mapping for " + segmentName, 0.95, 0.60);
        CompletionService completionService = new CompletionService() {
            @Override
            public CompletionResponse generateCompletion(CompletionRequest request) {
                return CompletionResponse.builder().text("<fragment><MENGE>10</MENGE></fragment>").build();
            }
        };

        EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(resolver, mappingChunkProvider, completionService, new com.retail.ai.edi.DeterministicEdiToIdocMapper());

        String ediXml = "<edi>"
                + "<segment name=\"UNB\"><field>UNOA:3</field><field>SENDER</field><field>RECEIVER</field></segment>"
                + "<segment name=\"BGM\"><field>220</field><field>PO12345</field></segment>"
                + "<segment name=\"DTM\"><field>137:20260717:102</field></segment>"
                + "<segment name=\"CUX\"><field>2:USD</field></segment>"
                + "<segment name=\"RFF\"><field>ON:PO12345</field></segment>"
                + "<segment name=\"UNH\"><field>1</field></segment>"
                + "<segment name=\"LIN\"><field>100:PCE</field></segment>"
                + "<segment name=\"QTY\"><field>21:5</field></segment>"
                + "<segment name=\"PRI\"><field>AAA:105</field></segment>"
                + "</edi>";
        EdiToIdocRagAssembler.AssemblyResult result = assembler.assemble(ediXml, "Levi's", "850");

        assertTrue(result.getFinalXml().contains("<EDI_DC40>"));
        assertTrue(result.getFinalXml().contains("<E1EDK01>"));
        assertTrue(result.getFinalXml().contains("<DOCNUM>1</DOCNUM>"));
        assertTrue(result.getFinalXml().contains("<SNDPRN>SENDER</SNDPRN>"));
        assertTrue(result.getFinalXml().contains("<RCVPRN>RECEIVER</RCVPRN>"));
        assertTrue(result.getFinalXml().contains("<BELNR>PO12345</BELNR>"));
        assertTrue(result.getFinalXml().contains("<WAERK>USD</WAERK>"));
        assertTrue(result.getFinalXml().contains("<POSEX>00010</POSEX>"));
    }

    @Test
    void shouldRejectStylesheetEchoFromLlm() {
        SegmentHierarchyRuleResolver resolver = new SegmentHierarchyRuleResolver("default", "Levi's", "850");
        MappingChunkProvider mappingChunkProvider = (tenant, transactionTypeCode, segmentName, rawSegment) -> MappingChunkResult.of("mapping for " + segmentName, 0.95, 0.60);
        CompletionService completionService = new CompletionService() {
            @Override
            public CompletionResponse generateCompletion(CompletionRequest request) {
                return CompletionResponse.builder().text("<xsl:template match=\"/\">\n<field>MATNR</field>\n</xsl:template>").build();
            }
        };

        EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(resolver, mappingChunkProvider, completionService, new com.retail.ai.edi.DeterministicEdiToIdocMapper());
        String ediXml = "<edi><segment name=\"IMD\"><field>ABC</field></segment></edi>";
        EdiToIdocRagAssembler.AssemblyResult result = assembler.assemble(ediXml, "Levi's", "850");

        assertEquals(1, result.getUnmappedSegments().size());
        assertEquals("IMD", result.getUnmappedSegments().get(0));
    }
}
