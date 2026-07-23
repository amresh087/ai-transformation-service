package com.retail.ai.edi;

public interface MappingChunkProvider {
    MappingChunkResult fetchMappingChunk(String tenant, String transactionTypeCode, String segmentName, String rawSegment);
}
