package com.theragenx.theragenx_api.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reassembles partial case observations returned for independent document chunks.
 * Chunk boundaries are never treated as case boundaries. Strong patient identifiers
 * create/locate groups; unanchored fragments follow the current report sequence.
 */
@Component
public class CaseEvidenceAssembler {
    private final ObjectMapper mapper;

    public CaseEvidenceAssembler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ArrayNode assemble(List<JsonNode> fragments) {
        List<Group> groups = new ArrayList<>();
        Map<String, Group> byPatient = new LinkedHashMap<>();
        Group current = null;

        for (JsonNode fragment : fragments) {
            if (!hasAnyEvidence(fragment)) continue;
            String patientKey = patientKey(fragment);
            Group target;
            if (!patientKey.isBlank()) {
                target = byPatient.get(patientKey);
                if (target == null) {
                    target = new Group((ObjectNode) fragment.deepCopy());
                    groups.add(target);
                    byPatient.put(patientKey, target);
                } else {
                    mergeCase(target.caseNode, fragment);
                }
                current = target;
            } else if (current != null) {
                mergeCase(current.caseNode, fragment);
                target = current;
            } else if (groups.size() == 1) {
                target = groups.get(0);
                mergeCase(target.caseNode, fragment);
                current = target;
            } else {
                // No reliable patient association yet. Keep a non-empty fragment intact
                // rather than silently assigning it to an arbitrary patient.
                target = new Group((ObjectNode) fragment.deepCopy());
                groups.add(target);
                current = target;
            }
        }

        ArrayNode result = mapper.createArrayNode();
        for (Group group : groups) {
            if (hasAnyEvidence(group.caseNode)) result.add(group.caseNode);
        }
        return result;
    }

    private boolean hasAnyEvidence(JsonNode caseNode) {
        JsonNode fields = caseNode == null ? null : caseNode.path("fields");
        if (!fields.isObject()) return false;
        var names = fields.fieldNames();
        while (names.hasNext()) {
            JsonNode field = fields.path(names.next());
            if (!"extracted".equals(field.path("status").asText())) continue;
            JsonNode value = field.get("value");
            if (value == null || value.isNull()) continue;
            if (value.isTextual() && value.asText().isBlank()) continue;
            if (value.isArray() && value.isEmpty()) continue;
            if (field.path("evidence").isArray() && !field.path("evidence").isEmpty()) return true;
        }
        return false;
    }

    private String patientKey(JsonNode caseNode) {
        JsonNode fields = caseNode.path("fields");
        String initials = extractedValue(fields.path("patient_initials"));
        if (!initials.isBlank()) return "initials:" + normalize(initials);
        // Patient ID is not guaranteed to exist in every catalog. Use it only if present.
        String patientId = extractedValue(fields.path("patient_id"));
        if (!patientId.isBlank()) return "id:" + normalize(patientId);
        return "";
    }

    private String extractedValue(JsonNode field) {
        if (!"extracted".equals(field.path("status").asText())) return "";
        JsonNode value = field.get("value");
        return value == null || value.isNull() || !value.isValueNode() ? "" : value.asText("").trim();
    }

    private void mergeCase(ObjectNode target, JsonNode incoming) {
        JsonNode incomingFields = incoming.path("fields");
        if (!incomingFields.isObject()) return;
        ObjectNode targetFields = target.with("fields");
        incomingFields.fields().forEachRemaining(entry -> {
            String fieldId = entry.getKey();
            JsonNode incomingField = entry.getValue();
            if (!"extracted".equals(incomingField.path("status").asText())) return;
            JsonNode incomingValue = incomingField.get("value");
            if (incomingValue == null || incomingValue.isNull()) return;

            JsonNode existing = targetFields.get(fieldId);
            if (existing == null || !"extracted".equals(existing.path("status").asText())
                    || existing.path("value").isMissingNode() || existing.path("value").isNull()) {
                targetFields.set(fieldId, incomingField.deepCopy());
                return;
            }
            ObjectNode mergedField = (ObjectNode) existing;
            mergeEvidence(mergedField, incomingField);
            JsonNode existingValue = mergedField.get("value");
            if (existingValue != null && existingValue.equals(incomingValue)) return;

            if (isCollectionField(existingValue) || isCollectionField(incomingValue)) {
                ArrayNode union = mapper.createArrayNode();
                addUnique(union, existingValue);
                addUnique(union, incomingValue);
                mergedField.set("value", union);
            } else {
                // Preserve report order: a later explicit scalar observation is the
                // latest observation (e.g. dose increased from 15 mg to 20 mg).
                mergedField.set("value", incomingValue.deepCopy());
                mergedField.put("note", "Multiple values were reported across document sections; the latest source occurrence is shown.");
                mergedField.put("confidence", lowerConfidence(mergedField.path("confidence").asText(), incomingField.path("confidence").asText()));
            }
        });
    }

    private boolean isCollectionField(JsonNode value) { return value != null && value.isArray(); }

    private void addUnique(ArrayNode target, JsonNode value) {
        if (value == null || value.isNull()) return;
        if (value.isArray()) {
            value.forEach(item -> { if (!containsEquivalent(target, item)) target.add(item.deepCopy()); });
        } else if (!containsEquivalent(target, value)) target.add(value.deepCopy());
    }

    private boolean containsEquivalent(ArrayNode values, JsonNode candidate) {
        for (JsonNode existing : values) if (normalize(existing.asText()).equals(normalize(candidate.asText()))) return true;
        return false;
    }

    private void mergeEvidence(ObjectNode target, JsonNode incoming) {
        ArrayNode evidence = target.withArray("evidence");
        JsonNode incomingEvidence = incoming.path("evidence");
        if (!incomingEvidence.isArray()) return;
        Set<String> seen = new LinkedHashSet<>();
        evidence.forEach(item -> seen.add(item.path("quote").asText()));
        incomingEvidence.forEach(item -> {
            String quote = item.path("quote").asText();
            if (!quote.isBlank() && seen.add(quote)) evidence.add(item.deepCopy());
        });
    }

    private String lowerConfidence(String first, String second) {
        List<String> order = List.of("high", "medium", "low");
        int a = order.indexOf(first), b = order.indexOf(second);
        return order.get(Math.max(Math.max(a, b), 0));
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    private record Group(ObjectNode caseNode) { }
}
