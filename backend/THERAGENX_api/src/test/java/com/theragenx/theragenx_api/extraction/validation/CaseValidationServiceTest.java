package com.theragenx.theragenx_api.extraction.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaseValidationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CaseValidationService service = new CaseValidationService();

    @Test
    void acceptsPatientIdentifiedByAgeOnlyWhenOtherThreeElementsExist() throws Exception {
        var caseNode = mapper.readTree("""
                {"fields":{
                  "patient_initials":{"status":"unknown"},
                  "patient_age_years":{"status":"extracted","value":54},
                  "patient_sex":{"status":"unknown"},
                  "reporter_type":{"status":"extracted","value":"physician"},
                  "suspect_drug_name":{"status":"extracted","value":"amoxicillin"},
                  "event_terms":{"status":"extracted","value":["rash"]}
                }}
                """);
        assertTrue(service.validate(caseNode).validCase());
    }

    @Test
    void rejectsCaseWhenOneOfFourMinimumElementsIsMissing() throws Exception {
        var caseNode = mapper.readTree("""
                {"fields":{
                  "patient_initials":{"status":"extracted","value":"MK"},
                  "reporter_type":{"status":"extracted","value":"physician"},
                  "suspect_drug_name":{"status":"extracted","value":"amoxicillin"},
                  "event_terms":{"status":"unknown"}
                }}
                """);
        var result = service.validate(caseNode);
        assertFalse(result.validCase());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("Adverse event")));
    }
}
