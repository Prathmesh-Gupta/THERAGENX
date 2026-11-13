package com.theragenx.theragenx_api.extraction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Conservative character-based chunker. The configured token budget is converted
 * using 2 characters/token to leave headroom for JSON-heavy clinical text.
 */
@Component
public class TokenAwareChunker {
    private static final int CHARS_PER_ESTIMATED_TOKEN = 2;
    private final int configuredMaxTokens;
    private final int overlapTokens;

    public TokenAwareChunker(
            @Value("${app.groq.chunk.max-tokens:900}") int maxTokens,
            @Value("${app.groq.chunk.overlap-tokens:60}") int overlapTokens) {
        this.configuredMaxTokens = Math.max(200, maxTokens);
        this.overlapTokens = Math.max(0, Math.min(overlapTokens, this.configuredMaxTokens / 4));
    }

    public List<String> split(String text) {
        return split(text, configuredMaxTokens);
    }

    public List<String> split(String text, int maxChunkTokens) {
        if (text == null || text.isBlank()) return List.of("");
        int effectiveTokens = Math.max(200, Math.min(configuredMaxTokens, maxChunkTokens));
        int maxChars = effectiveTokens * CHARS_PER_ESTIMATED_TOKEN;
        int overlapChars = Math.min(overlapTokens, effectiveTokens / 4) * CHARS_PER_ESTIMATED_TOKEN;

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + maxChars);
            if (end < text.length()) {
                int paragraph = text.lastIndexOf("\n\n", end);
                int line = text.lastIndexOf("\n", end);
                int boundary = Math.max(paragraph, line);
                if (boundary > start + maxChars / 2) end = boundary;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
            if (end >= text.length()) break;
            start = Math.max(start + 1, end - overlapChars);
        }
        return chunks.isEmpty() ? List.of(text) : chunks;
    }
}
