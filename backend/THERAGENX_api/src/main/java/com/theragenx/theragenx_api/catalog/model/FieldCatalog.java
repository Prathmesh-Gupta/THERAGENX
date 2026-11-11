package com.theragenx.theragenx_api.catalog.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FieldCatalog(
        String catalogVersion,
        String description,
        Map<String, String> statuses,
        List<String> confidenceLevels,
        List<CatalogSection> sections,
        List<FieldDefinition> fields
) {
}
