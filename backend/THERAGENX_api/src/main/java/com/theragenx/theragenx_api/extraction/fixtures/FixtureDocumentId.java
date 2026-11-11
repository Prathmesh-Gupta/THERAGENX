package com.theragenx.theragenx_api.extraction.fixtures;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FixtureDocumentId {
    private static final Pattern ID_PATTERN = Pattern.compile("(?m)^Document ID: ([A-Za-z0-9_-]+)\\s*$");
    private FixtureDocumentId() { }

    public static String fromPrompt(String prompt) {
        Matcher matcher = ID_PATTERN.matcher(prompt == null ? "" : prompt);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Prompt does not contain a Document ID line.");
        }
        return matcher.group(1);
    }
}
