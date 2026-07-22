package com.retail.ai.service;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OllamaCompletionService implements CompletionService {

    private final RestTemplate restTemplate;

    @Value("${ollama.url}")
    private String ollamaUrl;

    @Value("${ollama.model}")
    private String model;

    @Override
    public CompletionResponse generateCompletion(CompletionRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("prompt", request.getPrompt());
        payload.put("stream", false);

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0);
        payload.put("options", options);

        Map<?, ?> response = restTemplate.postForObject(ollamaUrl, payload, Map.class);
        String text = response != null && response.get("response") != null ? response.get("response").toString() : "";
        return CompletionResponse.builder().text(text).model(model).build();
    }
}
