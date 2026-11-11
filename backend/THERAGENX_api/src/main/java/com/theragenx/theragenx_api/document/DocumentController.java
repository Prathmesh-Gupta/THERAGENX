package com.theragenx.theragenx_api.document;

import com.theragenx.theragenx_api.document.model.DocumentDetail;
import com.theragenx.theragenx_api.document.model.DocumentSummary;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documentService;
    public DocumentController(DocumentService documentService) { this.documentService = documentService; }

    @GetMapping
    public List<DocumentSummary> getAllDocuments() { return documentService.getAllDocuments(); }

    @GetMapping("/{documentId}")
    public DocumentDetail getDocument(@PathVariable String documentId) {
        return documentService.getDocumentDetail(documentId);
    }
}
