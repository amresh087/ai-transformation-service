package com.retail.ai.edi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class EdiSegment {
    private int sequenceIndex;
    private String name;
    @Builder.Default
    private List<String> fields = new ArrayList<>();
    private String rawXml;
}
