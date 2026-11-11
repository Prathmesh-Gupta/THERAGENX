package com.theragenx.theragenx_api.extraction.model;

import com.fasterxml.jackson.databind.JsonNode;

public record ExtractionResult(String documentId, String provider, String model, JsonNode result) {
}
