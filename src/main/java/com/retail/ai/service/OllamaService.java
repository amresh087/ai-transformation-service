package com.retail.ai.service;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.retail.ai.dto.EmbeddingRequest;
import com.retail.ai.dto.EmbeddingResponse;
import com.retail.ai.utilty.PromptHelper;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.SearchPoints;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class OllamaService {

    private final RestTemplate restTemplate;

    private final QdrantClient qdrantClient;

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.embeddings-url}")
    private String embeddingsUrl;

    @Value("${ollama.emd-model}")
    private String emdModel;

    public OllamaService(RestTemplate restTemplate, QdrantClient qdrantClient) {
        this.restTemplate = restTemplate;
        this.qdrantClient = qdrantClient;
    }

    /**
     * Processes the given command based on the specified session mode. Depending on the session mode, it generates a prompt, calls the Ollama API, and processes the response to return a structured JSON string.
     * @param command
     * @param sessionMode
     * @return
     * @throws RuntimeException
     */
    public String processUnified(String command, String sessionMode) throws RuntimeException {
    String prompt;
    String resultJsonString = null;
    ObjectMapper mapper = new ObjectMapper();

    if("EDI_XML".equalsIgnoreCase(sessionMode)){
        prompt = PromptHelper.getEdiToXmlPrompt(command);
        System.out.println("====prompt: === " + prompt);
        resultJsonString = executeOllamaCall(prompt);
    }
     else {

              prompt = PromptHelper.getProductPrompt(command);
              // llm response
              String llmJsonString = executeOllamaCall(prompt);
              ObjectNode llmRootNode = (ObjectNode) mapper.readTree(llmJsonString);
              String llmIntent = llmRootNode.get("intent").asString();
              if ("ADD_ITEM".equalsIgnoreCase(llmIntent)) {
                    String llMProductName = llmRootNode.get("productName").asString();
                    // Fetch the nearest product name match from Qdrant
                    String matchedProductName = searchTopProductField(llMProductName, "name");
                    llmRootNode.put("productName", matchedProductName);
                    resultJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmRootNode);

              } else {
                    // Fallback if intent is not ADD_ITEM but you still need to return the raw
                    // response
                    resultJsonString = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(llmRootNode);
              }
    }

    return resultJsonString;
}

/**
 * Helper method to handle common embedding generation and Qdrant vector searching.
 */
private String searchTopProductField(String textToEmbed, String payloadKey) {
    EmbeddingResponse embedding = createEmbedding(
        EmbeddingRequest.builder().prompt(textToEmbed).build()
    );

    List<Float> queryVector = embedding.getEmbedding().stream()
        .map(Double::floatValue)
        .toList();

    SearchPoints searchRequest = SearchPoints.newBuilder()
        .setCollectionName("products")
        .addAllVector(queryVector)
        .setLimit(1)
        .setWithPayload(WithPayloadSelectorFactory.enable(true))
        .build();

    try {
        List<ScoredPoint> qdrantResults = qdrantClient.searchAsync(searchRequest).get();
        
        if (qdrantResults != null && !qdrantResults.isEmpty()) {
            ScoredPoint point = qdrantResults.get(0);
            System.out.println("Vector Match Score: " + point.getScore());
            return point.getPayload().get(payloadKey).getStringValue();
        } else {
            throw new RuntimeException("No vector search results found for: " + textToEmbed);
        }
    } catch (InterruptedException | ExecutionException e) {
        Thread.currentThread().interrupt(); // Restore interrupted status if InterruptedException
        throw new RuntimeException("Failed to execute Qdrant vector search", e);
    }
}


/**
 * Creates an embedding for the given prompt using the Ollama API.  
 * @param request
 * @return
 */

    public EmbeddingResponse createEmbedding(EmbeddingRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", emdModel);
        payload.put("prompt", request.getPrompt());

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