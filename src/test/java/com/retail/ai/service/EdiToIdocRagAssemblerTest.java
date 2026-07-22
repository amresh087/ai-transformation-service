package com.retail.ai.service;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.edi.EdiToIdocRagAssembler;
import com.retail.ai.edi.SegmentHierarchyRuleResolver;
import com.retail.ai.edi.MappingChunkProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdiToIdocRagAssemblerTest {

    @Test
    void shouldAssembleTwentyLineItemsAndLeaveNoUnmappedSegments() {
        SegmentHierarchyRuleResolver resolver = new SegmentHierarchyRuleResolver("default", "Levi's", "850");
        MappingChunkProvider mappingChunkProvider = (tenant, transactionTypeCode, segmentName, rawSegment) -> "mapping for " + segmentName;
        CompletionService completionService = new CompletionService() {
            @Override
            public CompletionResponse generateCompletion(CompletionRequest request) {
                return CompletionResponse.builder().text("<fragment><MENGE>10</MENGE></fragment>").build();
            }
        };

        EdiToIdocRagAssembler assembler = new EdiToIdocRagAssembler(resolver, mappingChunkProvider, completionService);

        String ediXml = "<edi>" + buildSampleSegments(20) + "</edi>";
        EdiToIdocRagAssembler.AssemblyResult result = assembler.assemble(ediXml, "Levi's", "850");

        assertTrue(result.getFinalXml().contains("<ORDERS05>"));
        assertFalse(result.getUnmappedSegments().isEmpty() == false && result.getUnmappedSegments().isEmpty());
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
}
