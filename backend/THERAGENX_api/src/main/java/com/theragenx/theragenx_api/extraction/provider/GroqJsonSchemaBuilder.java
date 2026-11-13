package com.theragenx.theragenx_api.extraction.provider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.theragenx.theragenx_api.catalog.model.FieldCatalog;
import com.theragenx.theragenx_api.catalog.model.FieldDefinition;
import org.springframework.stereotype.Component;

/**
 * Builds the strict JSON schema sent to Groq.
 *
 * Keep values aligned with catalog types. Compatibility unions (for example
 * scalar OR singleton-array, or evidence object OR string) are deliberately
 * not used because Groq's strict-schema subset rejects nested/unsupported anyOf
 * variants. The extraction prompt tells the model the canonical representation.
 */
@Component
public class GroqJsonSchemaBuilder {

    private final ObjectMapper objectMapper;

    public GroqJsonSchemaBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode build(FieldCatalog catalog) {
        ObjectNode root = objectSchema();
        ObjectNode rootProperties = objectMapper.createObjectNode();

        rootProperties.set("document_id", stringSchema());
        rootProperties.set("cases", arraySchema(buildCaseSchema(catalog)));
        rootProperties.set("flags", arraySchema(stringSchema()));

        root.set("properties", rootProperties);
        addRequired(root, "document_id", "cases", "flags");
        return root;
    }

    /**
     * Conservative character-based estimate for the serialized schema.
     * This is a budgeting estimate, not a tokenizer count.
     */
    public int estimateTokens(FieldCatalog catalog) {
        try {
            int chars = objectMapper.writeValueAsString(build(catalog)).length();
            return Math.max(1, (int) Math.ceil(chars / 4.0));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to estimate Groq JSON schema size", e);
        }
    }

    private ObjectNode buildCaseSchema(FieldCatalog catalog) {
        ObjectNode caseSchema = objectSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode fieldsSchema = objectSchema();
        ObjectNode fieldProperties = objectMapper.createObjectNode();
        ArrayNode requiredFields = objectMapper.createArrayNode();

        for (FieldDefinition field : catalog.fields()) {
            if (field.id() == null || field.id().isBlank()) {
                throw new IllegalArgumentException("Catalog contains a field without an ID.");
            }
            fieldProperties.set(field.id(), buildFieldSchema(field));
            requiredFields.add(field.id());
        }

        fieldsSchema.set("properties", fieldProperties);
        fieldsSchema.set("required", requiredFields);
        properties.set("fields", fieldsSchema);
        caseSchema.set("properties", properties);
        addRequired(caseSchema, "fields");
        return caseSchema;
    }

    private ObjectNode buildFieldSchema(FieldDefinition field) {
        ObjectNode schema = objectSchema();
        ObjectNode properties = objectMapper.createObjectNode();

        properties.set("status", enumSchema("extracted", "unknown", "conflict"));
        properties.set("value", nullableSchema(valueSchema(field)));
        properties.set("confidence", enumSchema("high", "medium", "low"));
        properties.set("evidence", arraySchema(buildEvidenceSchema()));
        properties.set("note", nullableSchema(stringSchema()));

        schema.set("properties", properties);
        addRequired(schema, "status", "value", "confidence", "evidence", "note");
        return schema;
    }

    private ObjectNode valueSchema(FieldDefinition field) {
        return switch (field.type()) {
            case "string" -> stringSchema();
            case "integer" -> typeSchema("integer");
            case "number" -> typeSchema("number");
            case "date" -> stringSchema();
            case "string_list" -> arraySchema(stringSchema());
            case "coded" -> codedSchema(field);
            case "coded_multi" -> arraySchema(codedSchema(field));
            default -> throw new IllegalArgumentException(
                    "Unsupported catalog field type: " + field.type() + " for field " + field.id());
        };
    }

    private ObjectNode codedSchema(FieldDefinition field) {
        if (field.options() == null || field.options().isEmpty()) {
            throw new IllegalArgumentException("Coded field has no options: " + field.id());
        }
        ArrayNode options = objectMapper.createArrayNode();
        field.options().forEach(option -> options.add(option.code()));
        ObjectNode schema = typeSchema("string");
        schema.set("enum", options);
        return schema;
    }

    private ObjectNode buildEvidenceSchema() {
        ObjectNode evidence = objectSchema();
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("quote", stringSchema());
        evidence.set("properties", properties);
        addRequired(evidence, "quote");
        return evidence;
    }

    private ObjectNode nullableSchema(ObjectNode actualSchema) {
        ObjectNode nullable = objectMapper.createObjectNode();
        ArrayNode alternatives = objectMapper.createArrayNode();
        alternatives.add(actualSchema);
        alternatives.add(typeSchema("null"));
        nullable.set("anyOf", alternatives);
        return nullable;
    }

    private ObjectNode arraySchema(ObjectNode itemSchema) {
        ObjectNode schema = typeSchema("array");
        ObjectNode items = objectMapper.createObjectNode();
        items.setAll(itemSchema);
        schema.set("items", items);
        return schema;
    }

    private ObjectNode enumSchema(String... values) {
        ObjectNode schema = stringSchema();
        ArrayNode options = objectMapper.createArrayNode();
        for (String value : values) {
            options.add(value);
        }
        schema.set("enum", options);
        return schema;
    }

    private ObjectNode stringSchema() {
        return typeSchema("string");
    }

    private ObjectNode typeSchema(String type) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", type);
        return schema;
    }

    private ObjectNode objectSchema() {
        ObjectNode schema = typeSchema("object");
        schema.put("additionalProperties", false);
        return schema;
    }

    private void addRequired(ObjectNode schema, String... names) {
        ArrayNode required = objectMapper.createArrayNode();
        for (String name : names) {
            required.add(name);
        }
        schema.set("required", required);
    }
}
