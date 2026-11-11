package com.theragenx.theragenx_api.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.theragenx.theragenx_api.document.model.DocumentContent;
import com.theragenx.theragenx_api.document.model.DocumentDetail;
import com.theragenx.theragenx_api.document.model.DocumentSummary;
import com.theragenx.theragenx_api.extraction.store.ExtractionOutputStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentService {
    private final DocumentReader documentReader;
    private final ExtractionOutputStore outputStore;

    public DocumentService(DocumentReader documentReader, ExtractionOutputStore outputStore) {
        this.documentReader = documentReader;
        this.outputStore = outputStore;
    }

    public List<DocumentSummary> getAllDocuments() {
        List<DocumentSummary> summaries = new ArrayList<>();
        for (DocumentSummary source : documentReader.getAllDocuments()) {
            JsonNode output = outputStore.load(source.id()).orElse(null);
            int count = output != null && output.path("cases").isArray() ? output.path("cases").size() : 0;
            List<String> flags = new ArrayList<>();
            if (output != null && output.path("flags").isArray()) {
                output.path("flags").forEach(flag -> flags.add(flag.asText()));
            }
            String status = output == null ? "NOT_PROCESSED" : output.path("status").asText("COMPLETED");
            summaries.add(new DocumentSummary(source.id(), source.fileName(), source.format(),
                    source.characterCount(), count, flags, status));
        }
        return summaries;
    }

    public DocumentDetail getDocumentDetail(String documentId) {
        DocumentContent document = documentReader.getDocument(documentId);
        JsonNode output = outputStore.load(documentId).orElse(null);
        return new DocumentDetail(document, output, output != null);
    }

    /** Kept for extraction pipeline callers. */
    public DocumentContent getDocument(String documentId) { return documentReader.getDocument(documentId); }
    public String getDocumentsPath() { return documentReader.getDocumentsPath(); }
}
