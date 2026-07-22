package com.retail.ai.service;

import com.retail.ai.dto.CompletionRequest;
import com.retail.ai.dto.CompletionResponse;

public interface CompletionService {
    CompletionResponse generateCompletion(CompletionRequest request);
}
