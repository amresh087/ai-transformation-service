package com.retail.ai.edi;

public interface MappingChunkProvider {
    String fetchMappingChunk(String tenant, String transactionTypeCode, String segmentName, String rawSegment);
}
