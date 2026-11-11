package com.theragenx.theragenx_api.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.theragenx.theragenx_api.catalog.CatalogService;
import com.theragenx.theragenx_api.catalog.model.FieldCatalog;
import com.theragenx.theragenx_api.document.DocumentService;
import com.theragenx.theragenx_api.document.model.DocumentContent;
import com.theragenx.theragenx_api.extraction.duplicate.CaseDuplicateDetector;
import com.theragenx.theragenx_api.extraction.duplicate.DocumentDuplicateService;
import com.theragenx.theragenx_api.extraction.model.ExtractionResult;
import com.theragenx.theragenx_api.extraction.provider.LanguageModelProvider;
import com.theragenx.theragenx_api.extraction.store.ExtractionOutputStore;
import com.theragenx.theragenx_api.extraction.validation.CatalogValidator;
import com.theragenx.theragenx_api.extraction.validation.CaseValidationService;
import com.theragenx.theragenx_api.extraction.validation.EvidenceValidator;
import com.theragenx.theragenx_api.extraction.validation.StructuralValidator;
import com.theragenx.theragenx_api.extraction.validation.ValidationReport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExtractionService {
    private final DocumentService documentService;
    private final CatalogService catalogService;
    private final ExtractionPromptBuilder promptBuilder;
    private final LanguageModelProvider provider;
    private final ObjectMapper objectMapper;
    private final StructuralValidator structuralValidator;
    private final CatalogValidator catalogValidator;
    private final EvidenceValidator evidenceValidator;
    private final DocumentDuplicateService documentDuplicateService;
    private final CaseDuplicateDetector caseDuplicateDetector;
    private final CaseValidationService caseValidationService;
    private final ExtractionOutputStore outputStore;

    public ExtractionService(DocumentService documentService, CatalogService catalogService,
                             ExtractionPromptBuilder promptBuilder, LanguageModelProvider provider,
                             ObjectMapper objectMapper, StructuralValidator structuralValidator,
                             CatalogValidator catalogValidator, EvidenceValidator evidenceValidator,
                             DocumentDuplicateService documentDuplicateService,
                             CaseDuplicateDetector caseDuplicateDetector,
                             CaseValidationService caseValidationService,
                             ExtractionOutputStore outputStore) {
        this.documentService = documentService;
        this.catalogService = catalogService;
        this.promptBuilder = promptBuilder;
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.structuralValidator = structuralValidator;
        this.catalogValidator = catalogValidator;
        this.evidenceValidator = evidenceValidator;
        this.documentDuplicateService = documentDuplicateService;
        this.caseDuplicateDetector = caseDuplicateDetector;
        this.caseValidationService = caseValidationService;
        this.outputStore = outputStore;
    }

    public ExtractionResult extract(String documentId) {
        DocumentContent document = documentService.getDocument(documentId);
        DocumentDuplicateService.Check check =
                documentDuplicateService.beginProcessing(document.id(), document.content());

        if (check.state() == DocumentDuplicateService.State.COMPLETED) {

            // Same document: return previously saved extraction.
            if (check.originalDocumentId() == null) {
                var savedOutput = outputStore.load(documentId);

                if (savedOutput.isPresent()) {
                    return new ExtractionResult(
                            documentId,
                            provider.providerName(),
                            provider.modelName(),
                            savedOutput.get(),
                            new ValidationReport(List.of()),
                            "PROCESSED",
                            null
                    );
                }

                // Index exists but output is missing. Allow regeneration.
                documentDuplicateService.markFailed(documentId, document.content());
            } else {
                // Different document with identical content.
                ObjectNode duplicateResult = objectMapper.createObjectNode();
                duplicateResult.put("document_id", documentId);
                duplicateResult.put("status", "DUPLICATE");
                duplicateResult.put("duplicate_of", check.originalDocumentId());
                duplicateResult.set("cases", objectMapper.createArrayNode());

                return new ExtractionResult(
                        documentId,
                        provider.providerName(),
                        provider.modelName(),
                        duplicateResult,
                        new ValidationReport(List.of()),
                        "DUPLICATE",
                        check.originalDocumentId()
                );
            }
        }

        if (check.state() == DocumentDuplicateService.State.IN_PROGRESS) {
            ObjectNode pendingResult = objectMapper.createObjectNode();
            pendingResult.put("document_id", documentId);
            pendingResult.put("status", "IN_PROGRESS");
            pendingResult.set("cases", objectMapper.createArrayNode());
            return new ExtractionResult(documentId, provider.providerName(), provider.modelName(),
                    pendingResult, new ValidationReport(List.of()), "IN_PROGRESS", null);
        }

        try {
            FieldCatalog fieldCatalog = catalogService.getCatalog();
            String prompt = promptBuilder.build(document.id(), document.content(), fieldCatalog);
            String response = provider.generateJson(prompt);
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(response);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to parse LLM JSON response for " + documentId, exception);
            }
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalStateException("Model response must be a JSON object.");
            }
            ObjectNode json = (ObjectNode) parsed;
            JsonNode catalogJson = objectMapper.valueToTree(fieldCatalog);

            var structuralReport = structuralValidator.validate(json, catalogJson);
            var catalogReport = catalogValidator.validate(json, catalogJson);
            var evidenceReport = evidenceValidator.validateAndEnrich(json, document.content());
            List<String> allErrors = new ArrayList<>();
            allErrors.addAll(structuralReport.errors());
            allErrors.addAll(catalogReport.errors());
            allErrors.addAll(evidenceReport.errors());

            // Validate the four minimum case elements before any case enters the duplicate index.
            ArrayNode cases = json.withArray("cases");
            List<JsonNode> candidates = new ArrayList<>();
            List<Boolean> eligible = new ArrayList<>();
            List<Integer> originalIndexes = new ArrayList<>();
            ArrayNode rejectedCases = objectMapper.createArrayNode();
            for (int i = 0; i < cases.size(); i++) {
                JsonNode candidate = cases.get(i);
                var caseReport = caseValidationService.validate(candidate);
                if (candidate instanceof ObjectNode mutableCase) {
                    mutableCase.set("caseValidation", objectMapper.valueToTree(caseReport));
                }
                if (caseReport.validCase()) {
                    candidates.add(candidate);
                    eligible.add(true);
                    originalIndexes.add(i);
                } else {
                    ObjectNode rejected = objectMapper.createObjectNode();
                    rejected.put("sourceCaseIndex", i);
                    rejected.set("caseValidation", objectMapper.valueToTree(caseReport));
                    rejectedCases.add(rejected);
                    for (String error : caseReport.errors()) allErrors.add("cases[" + i + "]: " + error);
                }
            }

            List<Boolean> duplicateFlags = caseDuplicateDetector.compareAndRegister(
                    candidates, eligible, document.id());
            ArrayNode finalCases = objectMapper.createArrayNode();
            for (int i = 0; i < candidates.size(); i++) {
                ObjectNode mutableCase = (ObjectNode) candidates.get(i);
                boolean duplicate = duplicateFlags.get(i);
                mutableCase.put("possibleDuplicate", duplicate);
                if (duplicate) {
                    // Keep the case in the response for human review, but don't add it to the canonical index.
                    allErrors.add("cases[" + originalIndexes.get(i) + "]: Possible duplicate case detected.");
                }
                finalCases.add(mutableCase);
            }
            json.set("cases", finalCases);
            if (!rejectedCases.isEmpty()) json.set("rejectedCases", rejectedCases);
            if (!rejectedCases.isEmpty()) {
                ArrayNode flags = json.withArray("flags");
                if (!containsText(flags, "one_or_more_cases_missing_minimum_elements")) {
                    flags.add("one_or_more_cases_missing_minimum_elements");
                }
            }

            ValidationReport combinedReport = new ValidationReport(allErrors);
            // Persist the canonical extraction JSON before marking the document completed.
            outputStore.save(documentId, json);
            documentDuplicateService.markCompleted(document.id(), document.content());
            return new ExtractionResult(documentId, provider.providerName(), provider.modelName(),
                    json, combinedReport, "PROCESSED", null);
        } catch (RuntimeException exception) {
            documentDuplicateService.markFailed(document.id(), document.content());
            throw exception;
        }
    }

    private boolean containsText(ArrayNode array, String expected) {
        for (JsonNode item : array) if (expected.equals(item.asText())) return true;
        return false;
    }
}
