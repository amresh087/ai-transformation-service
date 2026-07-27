package com.retail.ai.service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.retail.ai.dto.EmbeddingRequest;
import com.retail.ai.dto.EmbeddingResponse;

import io.qdrant.client.QdrantClient;

@Service
public class OllamaService {

    private final RestTemplate restTemplate;

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.embeddings-url}")
    private String embeddingsUrl;

    @Value("${ollama.embedding-model}")
    private String emdModel;

    public OllamaService(RestTemplate restTemplate, QdrantClient qdrantClient) {
        this.restTemplate = restTemplate;
       
    }

    
/**
 * Creates an embedding for the given prompt using the Ollama API.  
 * @param request
 * @return
 */

    public EmbeddingResponse createEmbedding(EmbeddingRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", emdModel);
        payload.put("prompt", request.getChunkText());

        

        String endpoint = embeddingsUrl;
        Map<?, ?> response = restTemplate.postForObject(endpoint, payload, Map.class);
        if (response != null && response.get("embedding") instanceof List) {
            @SuppressWarnings("unchecked")
            List<Double> embedding = (List<Double>) response.get("embedding");
            return EmbeddingResponse.builder().embedding(embedding).build();
        }
        return EmbeddingResponse.builder().embedding(List.of()).build();
    }

    /**
     * Helper method to execute the Ollama API call with the given prompt and return
     * the response as a string.
     * 
     * @param prompt The prompt to send to the Ollama API.
     * @return The response from the Ollama API as a string.
     */
    private String executeOllamaCall(String prompt) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("stream", false);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0);
        request.put("options", options);

        Map<?, ?> response = restTemplate.postForObject(ollamaUrl, request, Map.class);
        if (response != null && response.get("response") != null) {
            return response.get("response").toString();
        }
        return "{}";
    }

 
}