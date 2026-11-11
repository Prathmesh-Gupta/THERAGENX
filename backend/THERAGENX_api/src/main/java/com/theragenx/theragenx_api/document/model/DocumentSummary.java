package com.theragenx.theragenx_api.document.model;

import java.util.List;

public record DocumentSummary(
        String id,
        String fileName,
        String format,
        long characterCount,
        int caseCount,
        List<String> flags,
        String extractionStatus
) {
    public DocumentSummary { flags = flags == null ? List.of() : List.copyOf(flags); }

    public DocumentSummary(String id, String fileName, String format, long characterCount) {
        this(id, fileName, format, characterCount, 0, List.of(), "NOT_PROCESSED");
    }
}
