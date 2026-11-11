package com.theragenx.theragenx_api.extraction.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.theragenx.theragenx_api.extraction.validation.ValidationReport;

public record ExtractionResult(String documentId, String provider, String model, JsonNode result,
                               ValidationReport validation, String status, String duplicateOf) {
}
