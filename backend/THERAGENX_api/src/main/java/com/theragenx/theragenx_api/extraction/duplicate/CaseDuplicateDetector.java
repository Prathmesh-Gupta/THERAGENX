package com.theragenx.theragenx_api.extraction.duplicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent semantic duplicate index. Only validated, non-duplicate cases are registered. */
@Component
public class CaseDuplicateDetector {
    private static final String SOURCE_DOCUMENT_ID = "_sourceDocumentId";
    private final ObjectMapper mapper;
    private final Path indexPath;
    private final List<JsonNode> indexedCases = new ArrayList<>();

    public CaseDuplicateDetector(ObjectMapper mapper,
            @Value("${app.duplicate.case-index-path:../../outputs/case-fingerprint-index.json}") String indexPath) {
        this.mapper = mapper;
        this.indexPath = Path.of(indexPath).toAbsolutePath().normalize();
        loadCases();
    }

    public boolean areLikelyDuplicates(JsonNode firstCase, JsonNode secondCase) {
        String firstPatient = normalized(value(firstCase, "patient_initials"));
        String secondPatient = normalized(value(secondCase, "patient_initials"));
        if (firstPatient.isBlank() || !firstPatient.equals(secondPatient)) return false;

        // Age and sex are supporting signals when both reports provide them.
        String firstAge = value(firstCase, "patient_age_years").trim();
        String secondAge = value(secondCase, "patient_age_years").trim();
        if (!firstAge.isBlank() && !secondAge.isBlank() && !firstAge.equals(secondAge)) return false;
        String firstSex = normalized(value(firstCase, "patient_sex"));
        String secondSex = normalized(value(secondCase, "patient_sex"));
        if (!firstSex.isBlank() && !secondSex.isBlank() && !firstSex.equals(secondSex)) return false;

        Set<String> firstEvents = eventTerms(firstCase);
        Set<String> secondEvents = eventTerms(secondCase);
        if (firstEvents.isEmpty() || secondEvents.isEmpty()) return false;
        Set<String> intersection = new LinkedHashSet<>(firstEvents);
        intersection.retainAll(secondEvents);
        Set<String> union = new LinkedHashSet<>(firstEvents);
        union.addAll(secondEvents);
        return !union.isEmpty() && (double) intersection.size() / union.size() >= 0.5;
    }

    /**
     * Compares cases with the persisted index and earlier eligible cases in this batch.
     * Invalid cases and cases already identified as duplicates are not added to the index.
     * Entries from the same source document are replaced, making retries idempotent.
     */
    public synchronized List<Boolean> compareAndRegister(List<JsonNode> newCases,
                                                          List<Boolean> eligible,
                                                          String sourceDocumentId) {
        if (newCases.size() != eligible.size()) {
            throw new IllegalArgumentException("newCases and eligible must have the same size");
        }
        List<JsonNode> baseline = new ArrayList<>();
        for (JsonNode existing : indexedCases) {
            if (!sourceDocumentId.equals(existing.path(SOURCE_DOCUMENT_ID).asText())) {
                baseline.add(existing);
            }
        }

        List<Boolean> duplicateFlags = new ArrayList<>(newCases.size());
        List<JsonNode> acceptedFromBatch = new ArrayList<>();
        List<JsonNode> entriesToRegister = new ArrayList<>();
        for (int i = 0; i < newCases.size(); i++) {
            JsonNode candidate = newCases.get(i);
            if (!eligible.get(i)) {
                duplicateFlags.add(false);
                continue;
            }
            boolean duplicate = baseline.stream().anyMatch(existing -> areLikelyDuplicates(candidate, existing))
                    || acceptedFromBatch.stream().anyMatch(existing -> areLikelyDuplicates(candidate, existing));
            duplicateFlags.add(duplicate);
            if (!duplicate) {
                acceptedFromBatch.add(candidate);
                ObjectNode stored = candidate.deepCopy();
                stored.put(SOURCE_DOCUMENT_ID, sourceDocumentId);
                entriesToRegister.add(stored);
            }
        }

        List<JsonNode> updated = new ArrayList<>(baseline);
        updated.addAll(entriesToRegister);
        persistCases(updated); // Persist before changing in-memory state.
        indexedCases.clear();
        indexedCases.addAll(updated);
        return duplicateFlags;
    }

    /** Backward-compatible convenience method; callers should use eligibility-aware overload. */
    public synchronized List<Boolean> compareAndRegister(List<JsonNode> newCases) {
        return compareAndRegister(newCases, java.util.Collections.nCopies(newCases.size(), true), "legacy-batch");
    }

    private void loadCases() {
        if (!Files.isRegularFile(indexPath)) return;
        try {
            JsonNode root = mapper.readTree(indexPath.toFile());
            if (root != null && root.isArray()) root.forEach(item -> indexedCases.add(item.deepCopy()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read case duplicate index: " + indexPath, exception);
        }
    }

    private void persistCases(List<JsonNode> cases) {
        Path temporaryPath = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(indexPath.getParent());
            ArrayNode root = mapper.createArrayNode();
            cases.forEach(root::add);
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), root);
            try {
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveException) {
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save case duplicate index: " + indexPath, exception);
        }
    }

    private String value(JsonNode caseNode, String fieldId) {
        JsonNode value = caseNode.path("fields").path(fieldId).path("value");
        if (value.isValueNode()) return value.asText();
        return "";
    }

    private Set<String> eventTerms(JsonNode caseNode) {
        JsonNode value = caseNode.path("fields").path("event_terms").path("value");
        Set<String> terms = new LinkedHashSet<>();
        if (value.isArray()) value.forEach(item -> terms.add(normalized(item.asText())));
        else if (value.isTextual()) terms.add(normalized(value.asText()));
        terms.remove("");
        return terms;
    }

    private String normalized(String input) {
        return Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFKC)
                .toLowerCase().replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }
}
