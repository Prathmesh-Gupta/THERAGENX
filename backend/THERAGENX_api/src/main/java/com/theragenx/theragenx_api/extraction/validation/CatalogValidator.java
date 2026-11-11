package com.theragenx.theragenx_api.extraction.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates extracted values against the rules and options in field_catalog.json.
 * Field IDs, patterns, ranges and option codes are read from the supplied catalog.
 */
@Component
public class CatalogValidator {

    public ValidationReport validate(JsonNode extraction, JsonNode catalog) {
        List<String> errors = new ArrayList<>();
        JsonNode definitions = catalog == null ? null : catalog.path("fields");
        JsonNode cases = extraction == null ? null : extraction.path("cases");
        if (definitions == null || !definitions.isArray()) {
            return new ValidationReport(List.of("Catalog property 'fields' must be an array."));
        }
        if (cases == null || !cases.isArray()) {
            return new ValidationReport(List.of("Extraction property 'cases' must be an array."));
        }

        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            JsonNode fields = cases.get(caseIndex).path("fields");
            if (!fields.isObject()) continue; // StructuralValidator reports this.

            for (JsonNode definition : definitions) {
                String fieldId = text(definition, "id");
                if (fieldId == null) continue;
                JsonNode field = fields.path(fieldId);
                if (!"extracted".equals(text(field, "status"))) continue;
                JsonNode value = field.get("value");
                if (value == null || value.isNull()) continue; // StructuralValidator reports this.
                validateValue("cases[" + caseIndex + "].fields." + fieldId,
                        value, definition, errors);
            }
        }
        return new ValidationReport(errors);
    }

    private void validateValue(String path, JsonNode value, JsonNode definition, List<String> errors) {
        String type = text(definition, "type");
        JsonNode options = definition.path("options");
        if ("coded".equals(type) || "coded_multi".equals(type)) {
            Set<String> allowed = new HashSet<>();
            if (options.isArray()) {
                for (JsonNode option : options) {
                    String code = text(option, "code");
                    if (code != null) allowed.add(code);
                }
            }
            if ("coded".equals(type)) {
                if (!value.isTextual() || !allowed.contains(value.asText())) {
                    errors.add(path + ".value is not one of the catalog's allowed codes: " + allowed + ".");
                }
            } else {
                if (!value.isArray()) {
                    errors.add(path + ".value must be an array of catalog codes.");
                } else {
                    Set<String> seen = new HashSet<>();
                    for (int i = 0; i < value.size(); i++) {
                        JsonNode item = value.get(i);
                        if (!item.isTextual() || !allowed.contains(item.asText())) {
                            errors.add(path + ".value[" + i + "] is not a catalog-allowed code.");
                        } else if (!seen.add(item.asText())) {
                            errors.add(path + ".value contains duplicate code '" + item.asText() + "'.");
                        }
                    }
                    if (seen.contains("none") && seen.size() > 1) {
                        errors.add(path + ".value uses 'none' together with another seriousness criterion.");
                    }
                }
            }
        }

        JsonNode validation = definition.path("validation");
        if (validation.isObject()) {
            JsonNode patternNode = validation.get("pattern");
            if (patternNode != null && patternNode.isTextual() && value.isTextual()) {
                try {
                    if (!Pattern.matches(patternNode.asText(), value.asText())) {
                        errors.add(path + ".value does not match catalog pattern '" + patternNode.asText() + "'.");
                    }
                } catch (PatternSyntaxException exception) {
                    errors.add("Catalog contains an invalid regex for field '" + text(definition, "id") + "'.");
                }
            }
            if (value.isNumber()) {
                BigDecimal number = value.decimalValue();
                JsonNode min = validation.get("min");
                JsonNode max = validation.get("max");
                if (min != null && min.isNumber() && number.compareTo(min.decimalValue()) < 0) {
                    errors.add(path + ".value is below catalog minimum " + min.asText() + ".");
                }
                if (max != null && max.isNumber() && number.compareTo(max.decimalValue()) > 0) {
                    errors.add(path + ".value exceeds catalog maximum " + max.asText() + ".");
                }
            }
        }

        if ("date".equals(type) && value.isTextual()) {
            try {
                LocalDate.parse(value.asText());
            } catch (DateTimeParseException exception) {
                errors.add(path + ".value must be a valid ISO date (YYYY-MM-DD).");
            }
        }
    }

    private String text(JsonNode node, String property) {
        if (node == null) return null;
        JsonNode value = node.get(property);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
