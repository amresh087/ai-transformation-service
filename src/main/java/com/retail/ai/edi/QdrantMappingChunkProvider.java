package com.retail.ai.edi;

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

    @Value("${qdrant.collection-name:xslt_mappings}")
    private String collectionName;

    @Override
    public String fetchMappingChunk(String tenant, String transactionTypeCode, String segmentName, String rawSegment) {
        try {
            Points.SearchPoints searchPoints = Points.SearchPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .addAllVector(List.of(0.0f, 0.0f, 0.0f))
                    .setLimit(1)
                    .setWithPayload(WithPayloadSelectorFactory.enable(true))
                    .build();
            List<Points.ScoredPoint> results = qdrantClient.searchAsync(searchPoints).get();
            if (results == null || results.isEmpty()) {
                return null;
            }
            return results.get(0).getPayload().get("xsltChunkText").getStringValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }
}
