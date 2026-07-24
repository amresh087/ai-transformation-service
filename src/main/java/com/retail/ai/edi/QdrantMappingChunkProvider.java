package com.retail.ai.edi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.retail.ai.dto.EmbeddingRequest;
import com.retail.ai.service.OllamaService;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QdrantMappingChunkProvider implements MappingChunkProvider {

    private final QdrantClient qdrantClient;
    private final OllamaService ollamaService;

    @Value("${qdrant.collection-name:xslt_mappings}")
    private String collectionName;

    @Value("${mapping.similarity-threshold:0.60}")
    private double similarityThreshold;

    @Override
    public List<MappingChunk> fetchMappingChunk(String tenant, String transactionTypeCode, String segmentName,
            String rawSegment) {
        try {
            String queryText = buildQueryText(tenant, transactionTypeCode, segmentName, rawSegment);
            List<Double> embedding = ollamaService.createEmbedding(EmbeddingRequest.builder().prompt(queryText).build())
                    .getEmbedding();

            List<Float> queryVector = embedding.stream().map(Double::floatValue).toList();

            Points.SearchPoints searchPoints = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(3)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .build();

            List<Points.ScoredPoint> results = qdrantClient.searchAsync(searchPoints).get();

            List<MappingChunk> mappings = new ArrayList<>();

            for (Points.ScoredPoint point : results) {

                String chunk = point.getPayloadMap()
                        .getOrDefault("xsltChunkText", null)
                        .getStringValue();

                String xsltSegment = "";

                if (point.getPayloadMap().containsKey("segmentName")) {
                    xsltSegment = point.getPayloadMap()
                            .get("segmentName")
                            .getStringValue();
                }

                mappings.add(
                        MappingChunk.builder()
                                .chunkText(chunk)
                                .score(point.getScore())
                                .segmentName(xsltSegment)
                                .build());
            }

            return mappings;

        } catch (Exception e) {
            return Collections.emptyList();

        }

        
    }

    private String buildQueryText(String tenant, String transactionTypeCode, String segmentName, String rawSegment) {
        return String.format("tenant=%s transactionType=%s segment=%s rawSegment=%s",
                tenant != null ? tenant : "",
                transactionTypeCode != null ? transactionTypeCode : "",
                segmentName != null ? segmentName : "",
                rawSegment != null ? rawSegment : "");
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }
}
