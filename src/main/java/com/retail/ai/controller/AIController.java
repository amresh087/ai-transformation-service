package com.retail.ai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.retail.ai.dto.AIRequest;
import com.retail.ai.dto.EmbeddingRequest;
import com.retail.ai.dto.EmbeddingResponse;
import com.retail.ai.service.OllamaService;

@RestController
@RequestMapping("/ai")
// @CrossOrigin("*")
public class AIController {

    private final OllamaService ollamaService;

    public AIController(
            OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    @PostMapping("/intent")
    public String getIntent(@RequestBody AIRequest request, @RequestParam(required = false) String sessionMode) {
        // We pass the sessionMode flag from the frontend directly to the service layer
        return ollamaService.processUnified(request.getCommand(), sessionMode);
    }

    @PostMapping("/embeddings")
    public ResponseEntity<EmbeddingResponse> createEmbedding(@RequestBody EmbeddingRequest request) {
        EmbeddingResponse response = ollamaService.createEmbedding(request);
        return ResponseEntity.ok(response);
    }



}
