package com.retail.ai.dto;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResponse {
    private String intent;
    private String product;
    private Integer quantity;
    private String unit;

}
