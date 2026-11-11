package com.theragenx.theragenx_api.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theragenx.theragenx_api.catalog.CatalogService;
import com.theragenx.theragenx_api.document.DocumentService;
import com.theragenx.theragenx_api.document.model.DocumentContent;
import com.theragenx.theragenx_api.extraction.model.ExtractionResult;
import com.theragenx.theragenx_api.extraction.provider.LanguageModelProvider;
import org.springframework.stereotype.Service;

@Service
public class ExtractionService {
    private final DocumentService documentService;
    private final CatalogService catalogService;
    private final ExtractionPromptBuilder promptBuilder;
    private final LanguageModelProvider provider;
    private final ObjectMapper objectMapper;

    public ExtractionService(DocumentService documentService, CatalogService catalogService,
            ExtractionPromptBuilder promptBuilder, LanguageModelProvider provider,
            ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.catalogService = catalogService;
        this.promptBuilder = promptBuilder;
        this.provider = provider;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult extract(String documentId) {
        DocumentContent document = documentService.getDocument(documentId);
        String prompt = promptBuilder.build(document.id(), document.content(), catalogService.getCatalog());
        String response = provider.generateJson(prompt);
        try {
            JsonNode json = objectMapper.readTree(response);
            if (json == null || !json.isObject()) {
                throw new IllegalStateException("Model response must be a JSON object.");
            }
            return new ExtractionResult(documentId, provider.providerName(), provider.modelName(), json);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to parse Gemini JSON response for " + documentId, exception);
        }
    }
}
