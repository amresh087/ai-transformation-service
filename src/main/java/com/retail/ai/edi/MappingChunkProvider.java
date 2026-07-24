package com.retail.ai.edi;

import java.util.List;

public interface MappingChunkProvider {
    List<MappingChunk> fetchMappingChunk(String tenant, String transactionTypeCode, String segmentName, String rawSegment);
}
