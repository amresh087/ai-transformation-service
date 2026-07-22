package com.retail.ai.edi;

import com.retail.ai.dto.EmbeddingRequest;
import com.retail.ai.service.OllamaService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
@RequiredArgsConstructor
public class QdrantMappingChunkProvider implements MappingChunkProvider {

    private final QdrantClient qdrantClient;
    private final OllamaService ollamaService;

    @Value("${qdrant.collection-name:xslt_mappings}")
    private String collectionName;

    @Override
    public String fetchMappingChunk(String tenant, String transactionTypeCode, String segmentName, String rawSegment) {
        try {
            String queryText = buildQueryText(tenant, transactionTypeCode, segmentName, rawSegment);
            List<Double> embedding = ollamaService.createEmbedding(
                    EmbeddingRequest.builder().prompt(queryText).build()).getEmbedding();

            List<Float> queryVector = embedding.stream().map(Double::floatValue).toList();
            Points.SearchPoints searchPoints = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(queryVector)
                    .setLimit(1)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .build();
            List<Points.ScoredPoint> results = qdrantClient.searchAsync(searchPoints).get();
            if (results == null || results.isEmpty()) {
                return null;
            }
            Points.ScoredPoint scoredPoint = results.get(0);
            if (scoredPoint.getPayloadMap().containsKey("xsltChunkText")) {
                return scoredPoint.getPayloadMap().get("xsltChunkText").getStringValue();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    private String buildQueryText(String tenant, String transactionTypeCode, String segmentName, String rawSegment) {
        return String.format("tenant=%s transactionType=%s segment=%s rawSegment=%s",
                tenant != null ? tenant : "",
                transactionTypeCode != null ? transactionTypeCode : "",
                segmentName != null ? segmentName : "",
                rawSegment != null ? rawSegment : "");
    }
}
