package com.theragenx.theragenx_api.extraction.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Validates the structure and catalog-defined value types of an extraction result.
 * Field IDs and field types are read from the catalog; none are hard-coded here.
 * This validator does not verify whether a quote supports a value or calculate offsets.
 */
@Component
public class StructuralValidator {

    private static final Set<String> ALLOWED_STATUSES = Set.of("extracted", "unknown", "conflict");

    public ValidationReport validate(JsonNode extraction, JsonNode catalog) {
        List<String> errors = new ArrayList<>();

        if (extraction == null || !extraction.isObject()) {
            errors.add("Extraction result must be a JSON object.");
            return new ValidationReport(errors);
        }
        if (catalog == null || !catalog.isObject()) {
            errors.add("Field catalog must be a JSON object.");
            return new ValidationReport(errors);
        }

        JsonNode catalogFields = catalog.path("fields");
        if (!catalogFields.isArray()) {
            errors.add("Catalog property 'fields' must be an array.");
            return new ValidationReport(errors);
        }

        JsonNode cases = extraction.path("cases");
        if (!cases.isArray()) {
            errors.add("Extraction property 'cases' must be an array.");
            return new ValidationReport(errors);
        }

        Set<String> catalogIds = new HashSet<>();
        for (JsonNode definition : catalogFields) {
            String fieldId = text(definition, "id");
            if (fieldId == null) {
                errors.add("Every catalog field must have a non-empty id.");
                continue;
            }
            if (!catalogIds.add(fieldId)) {
                errors.add("Catalog contains duplicate field id: " + fieldId);
            }
        }

        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            JsonNode caseNode = cases.get(caseIndex);
            JsonNode values = caseNode.path("fields");
            String prefix = "cases[" + caseIndex + "].fields";

            if (!values.isObject()) {
                errors.add(prefix + " must be an object.");
                continue;
            }

            for (JsonNode definition : catalogFields) {
                String fieldId = text(definition, "id");
                if (fieldId == null) continue;

                JsonNode fieldValue = values.get(fieldId);
                if (fieldValue == null || fieldValue.isMissingNode()) {
                    errors.add(prefix + " is missing catalog field '" + fieldId + "'.");
                    continue;
                }
                validateField(prefix + "." + fieldId, fieldValue,
                        text(definition, "type"), errors);
            }

            Iterator<String> actualIds = values.fieldNames();
            while (actualIds.hasNext()) {
                String actualId = actualIds.next();
                if (!catalogIds.contains(actualId)) {
                    errors.add(prefix + " contains field not defined in catalog: '" + actualId + "'.");
                }
            }
        }

        return new ValidationReport(errors);
    }

    private void validateField(String path, JsonNode field, String catalogType, List<String> errors) {
        if (!field.isObject()) {
            errors.add(path + " must be an object.");
            return;
        }

        String status = text(field, "status");
        if (status == null || !ALLOWED_STATUSES.contains(status)) {
            errors.add(path + ".status must be one of: extracted, unknown, conflict.");
            return;
        }

        JsonNode value = field.get("value");
        if ("extracted".equals(status)) {
            if (value == null || value.isNull()) {
                errors.add(path + " has status extracted but no value.");
            } else if (!matchesType(value, catalogType)) {
                errors.add(path + ".value does not match catalog type '" + catalogType + "'.");
            }
            String confidence = text(field, "confidence");
            if (confidence == null || !Set.of("high", "medium", "low").contains(confidence)) {
                errors.add(path + ".confidence must be high, medium or low.");
            }
            JsonNode evidence = field.path("evidence");
            if (!evidence.isArray() || evidence.isEmpty()) {
                errors.add(path + ".evidence must contain at least one quote for an extracted value.");
            } else {
                for (int i = 0; i < evidence.size(); i++) {
                    String quote = text(evidence.get(i), "quote");
                    if (quote == null) {
                        errors.add(path + ".evidence[" + i + "].quote must be a non-empty string.");
                    }
                }
            }
        } else {
            if (value != null && !value.isNull()) {
                errors.add(path + " has status '" + status + "' and must not contain a value.");
            }
            if ("conflict".equals(status)) {
                String note = text(field, "note");
                if (note == null) {
                    errors.add(path + " has status conflict but no explanatory note.");
                }
            }
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        if (type == null) return false;
        return switch (type) {
            case "string", "coded", "date" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "string_list", "coded_multi" -> {
                boolean valid = value.isArray();
                if (valid) {
                    for (JsonNode item : value) {
                        if (!item.isTextual()) {
                            valid = false;
                            break;
                        }
                    }
                }
                yield valid;
            }
            default -> false;
        };
    }

    private String text(JsonNode node, String property) {
        if (node == null) return null;
        JsonNode value = node.get(property);
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText();
    }
}
