package com.theragenx.theragenx_api.extraction.duplicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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

/** Finds likely semantic duplicates AFTER extraction. It deliberately does not auto-delete or merge cases. */
@Component
public class CaseDuplicateDetector {
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

        Set<String> firstEvents = eventTerms(firstCase);
        Set<String> secondEvents = eventTerms(secondCase);
        if (firstEvents.isEmpty() || secondEvents.isEmpty()) return false;

        Set<String> intersection = new LinkedHashSet<>(firstEvents);
        intersection.retainAll(secondEvents);
        Set<String> union = new LinkedHashSet<>(firstEvents);
        union.addAll(secondEvents);
        double similarity = (double) intersection.size() / union.size();
        return similarity >= 0.5;
    }

    /** Compares against persisted cases and earlier cases in this batch, then persists the batch. */
    public synchronized List<Boolean> compareAndRegister(List<JsonNode> newCases) {
        List<Boolean> duplicateFlags = new ArrayList<>(newCases.size());
        List<JsonNode> batch = new ArrayList<>();
        for (JsonNode candidate : newCases) {
            boolean duplicate = indexedCases.stream().anyMatch(existing -> areLikelyDuplicates(candidate, existing))
                    || batch.stream().anyMatch(existing -> areLikelyDuplicates(candidate, existing));
            duplicateFlags.add(duplicate);
            batch.add(candidate.deepCopy());
        }
        List<JsonNode> updatedCases = new ArrayList<>(indexedCases);
        updatedCases.addAll(batch);
        persistCases(updatedCases);
        indexedCases.addAll(batch);
        return duplicateFlags;
    }

    /** Eligibility-aware registration used by extraction; incomplete cases remain in output but are not indexed. */
    public synchronized List<Boolean> compareAndRegister(List<JsonNode> newCases, List<Boolean> eligible, String sourceDocumentId) {
        if (newCases.size() != eligible.size()) throw new IllegalArgumentException("Case and eligibility lists must have the same size.");
        indexedCases.removeIf(item -> sourceDocumentId.equals(item.path("_sourceDocumentId").asText("")));
        List<Boolean> flags = new ArrayList<>(); List<JsonNode> additions = new ArrayList<>();
        for (int i=0;i<newCases.size();i++) {
            JsonNode candidate=newCases.get(i); boolean canIndex=Boolean.TRUE.equals(eligible.get(i));
            boolean duplicate=canIndex && (indexedCases.stream().anyMatch(existing -> areLikelyDuplicates(candidate,existing)) || additions.stream().anyMatch(existing -> areLikelyDuplicates(candidate,existing)));
            flags.add(duplicate);
            if(canIndex && !duplicate) { com.fasterxml.jackson.databind.node.ObjectNode stored=candidate.deepCopy() instanceof com.fasterxml.jackson.databind.node.ObjectNode o ? o : mapper.createObjectNode(); if(stored.isEmpty()) stored.set("case",candidate.deepCopy()); stored.put("_sourceDocumentId",sourceDocumentId); additions.add(stored); }
        }
        List<JsonNode> updated=new ArrayList<>(indexedCases); updated.addAll(additions); persistCases(updated); indexedCases.clear(); indexedCases.addAll(updated); return flags;
    }

    /** Returns likely matches from the persistent index without changing it. */
    public synchronized List<JsonNode> findMatches(List<JsonNode> candidates, String sourceDocumentId) {
        List<JsonNode> matches=new ArrayList<>();
        for(int i=0;i<candidates.size();i++) for(JsonNode indexed:indexedCases) {
            if(sourceDocumentId.equals(indexed.path("_sourceDocumentId").asText(""))) continue;
            JsonNode stored=indexed.has("case") ? indexed.path("case") : indexed;
            if(areLikelyDuplicates(candidates.get(i),stored)) { com.fasterxml.jackson.databind.node.ObjectNode result=mapper.createObjectNode(); result.put("caseIndex",i); result.put("sourceDocumentId",indexed.path("_sourceDocumentId").asText("Unknown document")); result.set("fields",stored.path("fields").deepCopy()); matches.add(result); }
        }
        return matches;
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
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveException) {
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save case duplicate index: " + indexPath, exception);
        }
    }

    private String value(JsonNode caseNode, String fieldId) {
        JsonNode value = caseNode.path("fields").path(fieldId).path("value");
        return value.isValueNode() ? value.asText() : "";
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
                .toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
