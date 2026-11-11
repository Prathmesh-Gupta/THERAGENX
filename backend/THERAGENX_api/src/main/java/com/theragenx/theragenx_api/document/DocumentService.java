package com.theragenx.theragenx_api.document;

import com.theragenx.theragenx_api.document.model.DocumentContent;
import com.theragenx.theragenx_api.document.model.DocumentSummary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DocumentService {

    private final DocumentReader documentReader;

    public DocumentService(DocumentReader documentReader) {
        this.documentReader = documentReader;
    }

    public List<DocumentSummary> getAllDocuments() {
        return documentReader.getAllDocuments();
    }

    public DocumentContent getDocument(String documentId) {
        return documentReader.getDocument(documentId);
    }

    public String getDocumentsPath() {
        return documentReader.getDocumentsPath();
    }
}
