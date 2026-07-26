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

    // IMPORTANT: Ollama's default context window is 2048 tokens unless
    // overridden here, REGARDLESS of what the underlying model actually
    // supports (e.g. a llama3.1 model supports 128k natively, but Ollama
    // will still cap you at 2048 unless num_ctx is set). Our IDoc mapping
    // prompts run ~20-25k characters (~5-6k tokens) once the rules/golden
    // example/segments/chunks are all included, so leaving this unset
    // silently truncates most of the prompt -- the model never actually
    // sees the real segment data, and produces ungrounded/generic output.
    //
    // Rough sizing: 1 token =~ 4 characters for English/XML-ish text.
    // Set this comfortably above (prompt tokens + expected output tokens).
    // Increasing this raises RAM/VRAM usage on the Ollama side, so if you
    // hit OOM errors, that's the trade-off to tune.
    @Value("${ollama.num-ctx:8192}")
    private int numCtx;

    @Override
    public CompletionResponse generateCompletion(CompletionRequest request) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("prompt", request.getPrompt());
        payload.put("stream", false);

        int approxPromptTokens = request.getPrompt().length() / 4;
        if (approxPromptTokens > numCtx * 0.9) {
            System.err.println("----> WARNING: prompt is ~" + approxPromptTokens
                    + " tokens, which is close to or exceeds num_ctx=" + numCtx
                    + ". Increase ollama.num-ctx or shorten the prompt, or "
                    + "the model will silently lose context.");
        }

        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0);
        options.put("num_ctx", numCtx);
        payload.put("options", options);

        Map<?, ?> response = restTemplate.postForObject(ollamaUrl, payload, Map.class);
        String text = response != null && response.get("response") != null ? response.get("response").toString() : "";
        return CompletionResponse.builder().text(text).model(model).build();
    }
}