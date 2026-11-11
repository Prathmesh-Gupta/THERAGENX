package com.theragenx.theragenx_api.extraction.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Verifies evidence quotes against the original report and writes source offsets.
 * Offsets are zero-based; start is inclusive and end is exclusive.
 */
@Component
public class EvidenceValidator {

    public ValidationReport validateAndEnrich(JsonNode extraction, String sourceText) {
        List<String> errors = new ArrayList<>();

        if (extraction == null || !extraction.isObject()) {
            errors.add("Evidence validation: extraction must be a JSON object.");
            return new ValidationReport(errors);
        }
        if (sourceText == null) {
            errors.add("Evidence validation: original document text is null.");
            return new ValidationReport(errors);
        }

        JsonNode cases = extraction.path("cases");
        if (!cases.isArray()) {
            errors.add("Evidence validation: extraction property 'cases' must be an array.");
            return new ValidationReport(errors);
        }

        NormalizedText source = NormalizedText.from(sourceText);

        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            JsonNode fields = cases.get(caseIndex).path("fields");
            if (!fields.isObject()) continue;

            var fieldNames = fields.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldId = fieldNames.next();
                JsonNode field = fields.get(fieldId);
                if (!"extracted".equals(field.path("status").asText())) continue;

                JsonNode evidence = field.path("evidence");
                String prefix = "cases[" + caseIndex + "].fields." + fieldId;
                if (!evidence.isArray() || evidence.isEmpty()) {
                    errors.add(prefix + " has an extracted value but no evidence quote.");
                    continue;
                }

                for (int evidenceIndex = 0; evidenceIndex < evidence.size(); evidenceIndex++) {
                    JsonNode item = evidence.get(evidenceIndex);
                    String quote = item.path("quote").asText(null);
                    String path = prefix + ".evidence[" + evidenceIndex + "]";
                    if (quote == null || quote.isBlank()) {
                        errors.add(path + " has an empty or missing quote.");
                        continue;
                    }

                    NormalizedText normalizedQuote = NormalizedText.from(quote);
                    if (normalizedQuote.value.isEmpty()) {
                        errors.add(path + " has an empty quote after normalization.");
                        continue;
                    }

                    int match = source.value.indexOf(normalizedQuote.value);
                    if (match < 0) {
                        errors.add(path + " quote was not found in the original document: \"" + quote + "\"");
                        continue;
                    }

                    int start = source.originalStart.get(match);
                    int end = source.originalEnd.get(match + normalizedQuote.value.length() - 1);
                    if (item instanceof ObjectNode evidenceObject) {
                        evidenceObject.put("start", start);
                        evidenceObject.put("end", end);
                    } else {
                        errors.add(path + " must be a JSON object to store calculated offsets.");
                    }
                }
            }
        }

        return new ValidationReport(errors);
    }

    /** Normalizes case and collapses whitespace while retaining source character mappings. */
    private static final class NormalizedText {
        private final String value;
        private final List<Integer> originalStart;
        private final List<Integer> originalEnd;

        private NormalizedText(String value, List<Integer> originalStart, List<Integer> originalEnd) {
            this.value = value;
            this.originalStart = originalStart;
            this.originalEnd = originalEnd;
        }

        private static NormalizedText from(String input) {
            StringBuilder normalized = new StringBuilder();
            List<Integer> starts = new ArrayList<>();
            List<Integer> ends = new ArrayList<>();

            for (int i = 0; i < input.length();) {
                int codePoint = input.codePointAt(i);
                int width = Character.charCount(codePoint);
                if (Character.isWhitespace(codePoint)) {
                    int whitespaceStart = i;
                    int whitespaceEnd = i + width;
                    i += width;
                    while (i < input.length()) {
                        int next = input.codePointAt(i);
                        if (!Character.isWhitespace(next)) break;
                        whitespaceEnd = i + Character.charCount(next);
                        i += Character.charCount(next);
                    }
                    if (normalized.length() > 0) {
                        normalized.append(' ');
                        starts.add(whitespaceStart);
                        ends.add(whitespaceEnd);
                    }
                } else {
                    // Preserve UTF-16 indexing used by String.substring(start, end).
                    for (int offset = 0; offset < width; offset++) {
                        char ch = input.charAt(i + offset);
                        normalized.append(String.valueOf(ch).toLowerCase(Locale.ROOT));
                        starts.add(i + offset);
                        ends.add(i + offset + 1);
                    }
                    i += width;
                }
            }

            // Trailing whitespace is intentionally omitted from normalized text.
            return new NormalizedText(normalized.toString(), starts, ends);
        }
    }
}
