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
import com.theragenx.theragenx_api.extraction.provider.GroqJsonSchemaBuilder;
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
    private final TokenAwareChunker chunker;
    private final int groqRequestBudget;
    private final int groqSchemaReserveTokens;
    private final GroqJsonSchemaBuilder groqSchemaBuilder;
    private final CaseEvidenceAssembler caseEvidenceAssembler;

    public ExtractionService(
            DocumentService documentService,
            CatalogService catalogService,
            ExtractionPromptBuilder promptBuilder,
            LanguageModelProvider provider,
            ObjectMapper objectMapper,
            StructuralValidator structuralValidator,
            CatalogValidator catalogValidator,
            EvidenceValidator evidenceValidator,
            DocumentDuplicateService documentDuplicateService,
            CaseDuplicateDetector caseDuplicateDetector,
            CaseValidationService caseValidationService,
            ExtractionOutputStore outputStore,
            TokenAwareChunker chunker,
            @org.springframework.beans.factory.annotation.Value("${app.groq.request.max-input-tokens:6000}") int groqRequestBudget,
            @org.springframework.beans.factory.annotation.Value("${app.groq.request.schema-reserve-tokens:300}") int groqSchemaReserveTokens,
            GroqJsonSchemaBuilder groqSchemaBuilder,
            CaseEvidenceAssembler caseEvidenceAssembler) {

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
        this.chunker = chunker;
        this.groqRequestBudget = Math.max(1000, groqRequestBudget);
        this.groqSchemaReserveTokens = Math.max(100, groqSchemaReserveTokens);
        this.groqSchemaBuilder = groqSchemaBuilder;
        this.caseEvidenceAssembler = caseEvidenceAssembler;
    }

    // Normal extraction. Existing saved results are reused.
    public ExtractionResult extract(String documentId) {
        return processDocument(documentId, false);
    }

    // Explicit re-extraction. The LLM is invoked again.
    public ExtractionResult reextract(String documentId) {
        return processDocument(documentId, true);
    }

    private ExtractionResult processDocument(String documentId, boolean force) {

        DocumentContent document = documentService.getDocument(documentId);

        DocumentDuplicateService.Check check = force
                ? documentDuplicateService.beginReprocessing(
                document.id(), document.content())
                : documentDuplicateService.beginProcessing(
                document.id(), document.content());

        if (check.state() == DocumentDuplicateService.State.COMPLETED) {

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

                documentDuplicateService.markFailed(
                        documentId, document.content());

            } else {

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

            return new ExtractionResult(
                    documentId,
                    provider.providerName(),
                    provider.modelName(),
                    pendingResult,
                    new ValidationReport(List.of()),
                    "IN_PROGRESS",
                    null
            );
        }

        try {
            FieldCatalog fieldCatalog = catalogService.getCatalog();

            // Budget the FIRST request as a cache miss. Reserve space for the JSON schema,
            // message metadata and estimation error; later cache hits improve throughput.
            int requestBudget = groqRequestBudget;
            int staticPromptTokens = promptBuilder.estimateStablePrefixTokens(fieldCatalog);
            int schemaAndProtocolReserve = Math.max(
                    groqSchemaReserveTokens, groqSchemaBuilder.estimateTokens(fieldCatalog));
            int metadataReserve = 200;
            int availableChunkTokens = requestBudget - staticPromptTokens
                    - schemaAndProtocolReserve - metadataReserve;
            if (availableChunkTokens < 200) {
                throw new IllegalStateException("The extraction rules/catalog leave only "
                        + availableChunkTokens + " tokens for report content. Reduce the catalog/prompt "
                        + "or increase the Groq request budget within your account limit.");
            }
            List<String> chunks = chunker.split(document.content(), availableChunkTokens);
            ObjectNode json = objectMapper.createObjectNode();
            json.put("document_id", document.id());
            List<JsonNode> caseFragments = new ArrayList<>();
            ArrayNode mergedFlags = objectMapper.createArrayNode();

            for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
                String chunkText = chunks.get(chunkIndex);
                String chunkPrompt = promptBuilder.build(
                        document.id(), chunkText, fieldCatalog);
                String response = provider.generateJson(chunkPrompt);
                JsonNode chunkResult;
                try {
                    chunkResult = objectMapper.readTree(response);
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("Unable to parse LLM JSON response for " + documentId + " chunk " + (chunkIndex + 1), exception);
                }
                if (chunkResult == null || !chunkResult.isObject()) {
                    throw new IllegalStateException("Model response must be a JSON object for chunk " + (chunkIndex + 1));
                }
                JsonNode chunkCases = chunkResult.path("cases");
                if (chunkCases.isArray()) {
                    for (JsonNode candidate : chunkCases) {
                        if (candidate != null && candidate.isObject()) caseFragments.add(candidate.deepCopy());
                    }
                }
                JsonNode flags = chunkResult.path("flags");
                if (flags.isArray()) for (JsonNode flag : flags) mergedFlags.add(flag.deepCopy());
            }

            // Chunk responses are partial observations, not final cases. Reassemble them
            // in source-processing order before running any case-level validation.
            ArrayNode mergedCases = caseEvidenceAssembler.assemble(caseFragments);
            json.set("cases", mergedCases);
            json.set("flags", mergedFlags);
            JsonNode catalogJson = objectMapper.valueToTree(fieldCatalog);

            var structuralReport =
                    structuralValidator.validate(json, catalogJson);

            var catalogReport =
                    catalogValidator.validate(json, catalogJson);

            var evidenceReport =
                    evidenceValidator.validateAndEnrich(json, document.content());

            List<String> allErrors = new ArrayList<>();
            allErrors.addAll(structuralReport.errors());
            allErrors.addAll(catalogReport.errors());
            allErrors.addAll(evidenceReport.errors());

            // Validate cases for review and duplicate-detection eligibility.
            // Never discard an extracted case because minimum information is missing.
            ArrayNode cases = json.withArray("cases");

            List<JsonNode> candidates = new ArrayList<>();
            List<Boolean> eligible = new ArrayList<>();
            List<Integer> originalIndexes = new ArrayList<>();

            for (int i = 0; i < cases.size(); i++) {

                JsonNode candidate = cases.get(i);
                var caseReport = caseValidationService.validate(candidate);

                if (candidate instanceof ObjectNode mutableCase) {
                    mutableCase.set(
                            "caseValidation",
                            objectMapper.valueToTree(caseReport));
                }

                // Retain every case. Only complete cases enter duplicate detection.
                candidates.add(candidate);
                eligible.add(caseReport.validCase());
                originalIndexes.add(i);

                for (String error : caseReport.errors()) {
                    allErrors.add("cases[" + i + "]: " + error);
                }
            }

            // Same-document entries are replaced by CaseDuplicateDetector.
            // Ineligible cases are retained but must not be indexed as duplicates.
            List<Boolean> duplicateFlags =
                    caseDuplicateDetector.compareAndRegister(
                            candidates, eligible, document.id());

            ArrayNode finalCases = objectMapper.createArrayNode();

            for (int i = 0; i < candidates.size(); i++) {
                ObjectNode mutableCase = (ObjectNode) candidates.get(i);
                boolean duplicate = duplicateFlags.get(i);
                mutableCase.put("possibleDuplicate", duplicate);

                if (duplicate) {
                    allErrors.add(
                            "cases[" + originalIndexes.get(i)
                                    + "]: Possible duplicate case detected.");
                }

                finalCases.add(mutableCase);
            }

            json.set("cases", finalCases);

            ValidationReport combinedReport =
                    new ValidationReport(allErrors);

            // Save only after extraction and validation have completed.
            // Existing output remains untouched if extraction fails earlier.
            outputStore.save(documentId, json);

            documentDuplicateService.markCompleted(
                    document.id(), document.content());

            return new ExtractionResult(
                    documentId,
                    provider.providerName(),
                    provider.modelName(),
                    json,
                    combinedReport,
                    "PROCESSED",
                    null
            );

        } catch (RuntimeException exception) {

            documentDuplicateService.markFailed(
                    document.id(), document.content());

            throw exception;
        }
    }


}
