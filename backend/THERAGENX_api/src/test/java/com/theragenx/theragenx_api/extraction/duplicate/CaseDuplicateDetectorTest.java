package com.theragenx.theragenx_api.extraction.duplicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaseDuplicateDetectorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir Path tempDir;

    @Test
    void detectsDuplicatesAcrossBatchesAndPersistsOnlyCanonicalCases() throws Exception {
        Path index = tempDir.resolve("cases.json");
        CaseDuplicateDetector detector = new CaseDuplicateDetector(mapper, index.toString());
        JsonNode first = caseNode("MK", 54, "female", "rash", "amoxicillin");
        JsonNode duplicate = caseNode("MK", 54, "female", "rash", "amoxicillin");

        assertEquals(List.of(false), detector.compareAndRegister(List.of(first), List.of(true), "D01"));
        assertEquals(List.of(true), detector.compareAndRegister(List.of(duplicate), List.of(true), "D02"));

        JsonNode saved = mapper.readTree(index.toFile());
        assertEquals(1, saved.size());
        assertEquals("D01", saved.get(0).path("_sourceDocumentId").asText());
    }

    @Test
    void invalidCasesAreNotRegisteredAndRetryForSameDocumentIsIdempotent() throws Exception {
        Path index = tempDir.resolve("cases.json");
        CaseDuplicateDetector detector = new CaseDuplicateDetector(mapper, index.toString());
        JsonNode first = caseNode("MK", 54, "female", "rash", "amoxicillin");
        detector.compareAndRegister(List.of(first), List.of(true), "D01");
        JsonNode revised = caseNode("MK", 55, "female", "rash", "amoxicillin");
        detector.compareAndRegister(List.of(revised), List.of(true), "D01");

        JsonNode saved = mapper.readTree(index.toFile());
        assertEquals(1, saved.size());
        assertEquals(55, saved.get(0).path("fields").path("patient_age_years").path("value").asInt());

        JsonNode invalid = caseNode("AB", 20, "male", "", "drug");
        detector.compareAndRegister(List.of(invalid), List.of(false), "D03");
        assertEquals(1, mapper.readTree(index.toFile()).size());
    }

    private JsonNode caseNode(String initials, int age, String sex, String event, String drug) throws Exception {
        return mapper.readTree("""
                {"fields":{
                  "patient_initials":{"status":"extracted","value":"%s"},
                  "patient_age_years":{"status":"extracted","value":%d},
                  "patient_sex":{"status":"extracted","value":"%s"},
                  "suspect_drug_name":{"status":"extracted","value":"%s"},
                  "event_terms":{"status":"extracted","value":["%s"]}
                }}
                """.formatted(initials, age, sex, drug, event));
    }
}
