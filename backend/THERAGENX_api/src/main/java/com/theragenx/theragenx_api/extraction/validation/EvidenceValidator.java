package com.theragenx.theragenx_api.extraction.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;

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

                    Match match = locateEvidence(source, quote);
                    if (match == null) {
                        errors.add(path + " quote was not found in the original document: \"" + quote + "\"");
                        continue;
                    }

                    int start = source.originalStart.get(match.normalizedStart);
                    int end = source.originalEnd.get(match.normalizedEnd - 1);
                    if (item instanceof ObjectNode evidenceObject) {
                        // Groq sometimes abbreviates a quote with ellipses. Once its ordered
                        // anchors are located, replace it with the exact contiguous source text.
                        evidenceObject.put("quote", sourceText.substring(start, end));
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


    /**
     * Finds exact quotes, or safely expands an ellipsis-abbreviated quote by matching
     * every non-empty anchor in order. Ambiguous or missing anchors are rejected.
     */
    private static Match locateEvidence(NormalizedText source, String quote) {
        NormalizedText normalizedQuote = NormalizedText.from(quote);
        if (normalizedQuote.value.isEmpty()) return null;

        int exact = source.value.indexOf(normalizedQuote.value);
        if (exact >= 0) return new Match(exact, exact + normalizedQuote.value.length());

        // PDF/table extraction can preserve the same words while changing cell separators
        // or line wrapping. Recover only high-confidence ordered token sequences, then map
        // the envelope back to original source offsets.
        Match tokenMatch = locateOrderedTokens(source, normalizedizedTokens(normalizedQuote.value));
        if (tokenMatch != null) return tokenMatch;

        String expanded = normalizedQuote.value.replace("…", "...");
        if (!expanded.contains("...")) return null;

        String[] rawAnchors = expanded.split("\\.\\.\\.", -1);
        List<String> anchors = new ArrayList<>();
        for (String anchor : rawAnchors) {
            String cleaned = anchor.strip();
            if (!cleaned.isEmpty()) anchors.add(cleaned);
        }
        if (anchors.isEmpty()) return null;

        int firstStart = -1;
        int previousEnd = 0;
        for (int i = 0; i < anchors.size(); i++) {
            String anchor = anchors.get(i);
            int found = source.value.indexOf(anchor, previousEnd);
            if (found < 0) return null;
            // Reject an anchor if another occurrence exists before the next anchor could
            // disambiguate it. Ordered matching is intentionally conservative.
            int nextOccurrence = source.value.indexOf(anchor, found + 1);
            if (nextOccurrence >= 0 && (i == anchors.size() - 1 ||
                    source.value.indexOf(anchors.get(i + 1), found + anchor.length()) < 0 ||
                    source.value.indexOf(anchors.get(i + 1), nextOccurrence + anchor.length()) >= 0)) {
                return null;
            }
            if (i == 0) firstStart = found;
            previousEnd = found + anchor.length();
        }
        return firstStart >= 0 && previousEnd > firstStart ? new Match(firstStart, previousEnd) : null;
    }


    private static List<String> normalizedizedTokens(String value) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[\\p{L}\\p{N}]+")
                .matcher(value);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() > 1 || token.chars().anyMatch(Character::isDigit)) tokens.add(token);
        }
        return tokens;
    }

    /**
     * Conservative fallback for table text whose cell whitespace differs from the LLM quote.
     * Requires at least 90% of meaningful tokens in the same order and a bounded source span.
     */
    private static Match locateOrderedTokens(NormalizedText source, List<String> tokens) {
        if (tokens.size() < 7) return null;
        int bestStart = -1;
        int bestEnd = -1;
        int bestMatched = 0;
        int maxSpan = Math.min(1800, Math.max(180, tokens.stream().mapToInt(String::length).sum() * 4));
        for (int startToken = 0; startToken < tokens.size(); startToken++) {
            String first = tokens.get(startToken);
            int cursor = source.value.indexOf(first);
            while (cursor >= 0) {
                int start = cursor;
                int end = cursor + first.length();
                int matched = 1;
                int tokenIndex = startToken + 1;
                while (tokenIndex < tokens.size() && end - start <= maxSpan) {
                    String next = tokens.get(tokenIndex);
                    int found = source.value.indexOf(next, end);
                    if (found < 0 || found - start > maxSpan) break;
                    end = found + next.length();
                    matched++;
                    tokenIndex++;
                }
                if (matched > bestMatched && (double) matched / tokens.size() >= 0.90) {
                    bestMatched = matched;
                    bestStart = start;
                    bestEnd = end;
                }
                cursor = source.value.indexOf(first, cursor + 1);
            }
        }
        if (bestStart >= 0) {
            // Reject equally strong matches at different positions; a quote must resolve uniquely.
            int alternatives = 0;
            String first = tokens.get(0);
            int cursor = source.value.indexOf(first);
            while (cursor >= 0) {
                if (cursor != bestStart) {
                    int end = cursor + first.length();
                    int matched = 1;
                    for (int i = 1; i < tokens.size(); i++) {
                        int found = source.value.indexOf(tokens.get(i), end);
                        if (found < 0 || found - cursor > maxSpan) break;
                        end = found + tokens.get(i).length();
                        matched++;
                    }
                    if ((double) matched / tokens.size() >= 0.90) alternatives++;
                }
                cursor = source.value.indexOf(first, cursor + 1);
            }
            if (alternatives == 0) return new Match(bestStart, bestEnd);
        }
        return null;
    }

    private record Match(int normalizedStart, int normalizedEnd) { }

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
                int cp = input.codePointAt(i);
                int width = Character.charCount(cp);
                int originalStart = i;
                int originalEnd = i + width;
                i += width;
                // Ignore invisible formatting marks that commonly appear in copied report text.
                if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF || cp == 0x2060) continue;
                boolean whitespace = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x00A0;
                if (whitespace) {
                    while (i < input.length()) {
                        int next = input.codePointAt(i);
                        if (!(Character.isWhitespace(next) || Character.isSpaceChar(next) || next == 0x00A0)) break;
                        originalEnd = i + Character.charCount(next);
                        i += Character.charCount(next);
                    }
                    if (normalized.length() > 0) {
                        normalized.append(' '); starts.add(originalStart); ends.add(originalEnd);
                    }
                    continue;
                }
                String folded = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
                for (int j = 0; j < folded.length(); j++) {
                    normalized.append(folded.charAt(j)); starts.add(originalStart); ends.add(originalEnd);
                }
            }
            return new NormalizedText(normalized.toString(), starts, ends);
        }
    }
}
