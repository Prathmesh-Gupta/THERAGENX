package com.theragenx.theragenx_api.document.model;

public record DocumentContent(
        String id,
        String fileName,
        String format,
        long characterCount,
        String content
) {
}
