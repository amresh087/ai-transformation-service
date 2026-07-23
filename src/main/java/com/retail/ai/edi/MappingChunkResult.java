package com.retail.ai.edi;

public class MappingChunkResult {

    private final String chunkText;
    private final double score;
    private final double threshold;

    private MappingChunkResult(String chunkText, double score, double threshold) {
        this.chunkText = chunkText;
        this.score = score;
        this.threshold = threshold;
    }

    public static MappingChunkResult of(String chunkText, double score) {
        return new MappingChunkResult(chunkText, score, 0.60);
    }

    public static MappingChunkResult of(String chunkText, double score, double threshold) {
        return new MappingChunkResult(chunkText, score, threshold);
    }

    public static MappingChunkResult empty() {
        return new MappingChunkResult(null, 0.0, 0.60);
    }

    public String getChunkText() {
        return chunkText;
    }

    public double getScore() {
        return score;
    }

    public double getThreshold() {
        return threshold;
    }

    public boolean isEmpty() {
        return chunkText == null || chunkText.isBlank();
    }
}
