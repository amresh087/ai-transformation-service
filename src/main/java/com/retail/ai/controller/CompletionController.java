package com.retail.ai.controller;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;
import com.retail.ai.service.CompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class CompletionController {

    private final CompletionService completionService;

    @PostMapping("/completions")
    public ResponseEntity<CompletionResponse> generateCompletion(@RequestBody CompletionRequest request) {
        return ResponseEntity.ok(completionService.generateCompletion(request));
    }
}
