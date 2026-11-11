package com.theragenx.theragenx_api.document.model;

import com.fasterxml.jackson.databind.JsonNode;

public record DocumentDetail(DocumentContent document, JsonNode extraction, boolean extractionAvailable) { }
