package com.theragenx.theragenx_api.document.model;

public record DocumentSummary(
        String id,
        String fileName,
        String format,
        long characterCount
) {
}
