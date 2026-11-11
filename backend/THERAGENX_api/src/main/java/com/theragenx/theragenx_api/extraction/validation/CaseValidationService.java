package com.theragenx.theragenx_api.extraction.validation;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Implements the exercise's four minimum case elements. */
@Component
public class CaseValidationService {
    public CaseValidationResult validate(JsonNode caseNode) {
        List<String> errors = new ArrayList<>();
        JsonNode fields = caseNode == null ? null : caseNode.path("fields");

        // The exercise defines an identifiable patient as having at least one of initials, age or sex.
        boolean identifiablePatient = hasUsableValue(field(fields, "patient_initials"))
                || hasUsableValue(field(fields, "patient_age_years"))
                || hasUsableValue(field(fields, "patient_sex"));
        if (!identifiablePatient) {
            errors.add("Identifiable patient is missing: at least one of patient initials, age or sex is required.");
        }
        if (!hasUsableValue(field(fields, "reporter_type"))) {
            errors.add("Reporter is missing.");
        }
        if (!hasUsableValue(field(fields, "suspect_drug_name"))) {
            errors.add("Suspect drug is missing.");
        }
        if (!hasUsableValue(field(fields, "event_terms"))) {
            errors.add("Adverse event is missing.");
        }
        return new CaseValidationResult(errors.isEmpty(), errors);
    }

    private JsonNode field(JsonNode fields, String id) {
        return fields == null || fields.isMissingNode() ? null : fields.get(id);
    }

    private boolean hasUsableValue(JsonNode field) {
        if (field == null || !"extracted".equals(field.path("status").asText())) return false;
        JsonNode value = field.get("value");
        if (value == null || value.isNull() || value.isMissingNode()) return false;
        if (value.isTextual()) return !value.asText().isBlank();
        if (value.isArray()) return !value.isEmpty();
        if (value.isNumber()) return true;
        if (value.isBoolean()) return true;
        return value.isObject() && !value.isEmpty();
    }
}
