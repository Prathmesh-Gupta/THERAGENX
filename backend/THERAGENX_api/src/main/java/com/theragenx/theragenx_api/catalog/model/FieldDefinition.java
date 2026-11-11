package com.theragenx.theragenx_api.catalog.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FieldDefinition(
        String id,
        String label,
        String section,
        String type,
        List<CatalogOption> options,
        Map<String, Object> validation,
        String guidance
) {
}
