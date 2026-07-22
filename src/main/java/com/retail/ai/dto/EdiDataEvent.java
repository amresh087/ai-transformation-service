package com.retail.ai.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EdiDataEvent {

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("document_name")
    private String documentName;

    @JsonProperty("document_type")
    private String documentType;

    @JsonProperty("tenant")
    private String tenant;

    @JsonProperty("transaction_type_code")
    private String transactionTypeCode;

    @JsonProperty("mapping_type")
    private String mappingType;

    @JsonProperty("status")
    private String status;

    @JsonProperty("object_name")
    private String objectName;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("timestamp")
    private LocalDateTime timestamp;

    @JsonProperty("payload")
    private String payload;
}