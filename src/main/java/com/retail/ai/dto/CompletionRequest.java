package com.retail.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompletionRequest {
    private String prompt;
    private String tenant;
    private String transactionTypeCode;
    private String segmentName;
}
