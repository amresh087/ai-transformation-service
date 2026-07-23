package com.retail.ai.edi;

import com.retail.ai.dto.EmbeddingRequest;
import com.retail.ai.dto.EmbeddingResponse;
import com.retail.ai.service.OllamaService;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import com.google.common.util.concurrent.SettableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QdrantMappingChunkProviderTest {

    @Test
    void shouldFetchChunkTextFromQdrantUsingEmbeddingQuery() throws Exception {
        QdrantClient qdrantClient = mock(QdrantClient.class);
        OllamaService ollamaService = mock(OllamaService.class);

        QdrantMappingChunkProvider provider = new QdrantMappingChunkProvider(qdrantClient, ollamaService);
        ReflectionTestUtils.setField(provider, "collectionName", "mapping-documents");

        EmbeddingResponse embedding = EmbeddingResponse.builder().embedding(List.of(0.1, 0.2, 0.3)).build();
        when(ollamaService.createEmbedding(any(EmbeddingRequest.class))).thenReturn(embedding);

        Points.ScoredPoint scoredPoint = Points.ScoredPoint.newBuilder()
                .putPayload("xsltChunkText", JsonWithInt.Value.newBuilder().setStringValue("header mapping chunk").build())
                .build();
        SettableFuture<List<Points.ScoredPoint>> future = SettableFuture.create();
        future.set(List.of(scoredPoint));
        when(qdrantClient.searchAsync(any(Points.SearchPoints.class))).thenReturn(future);

        MappingChunkResult chunkResult = provider.fetchMappingChunk("tenant-a", "ORDERS", "UNH", "<segment>header</segment>");

        assertEquals("header mapping chunk", chunkResult.getChunkText());
        assertEquals(0.0, chunkResult.getScore());
        verify(ollamaService).createEmbedding(any(EmbeddingRequest.class));
    }
}
