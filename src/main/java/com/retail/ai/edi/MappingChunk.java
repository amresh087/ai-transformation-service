package com.retail.ai.edi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class MappingChunk {

    private String chunkText;

    private double score;

    private String segmentName;
}
