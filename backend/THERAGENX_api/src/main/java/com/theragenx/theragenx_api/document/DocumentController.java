package com.theragenx.theragenx_api.document;

import com.theragenx.theragenx_api.document.model.DocumentDetail;
import com.theragenx.theragenx_api.document.model.DocumentSummary;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService documentService;
    public DocumentController(DocumentService documentService) { this.documentService = documentService; }

    @GetMapping
    public List<DocumentSummary> getAllDocuments() { return documentService.getAllDocuments(); }

    @PostMapping(value = "/upload/check", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> checkDuplicate(@RequestParam("file") MultipartFile file) { return documentService.checkDuplicate(file); }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String,Object>> upload(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "false") boolean allowDuplicate) {
        String id = documentService.uploadDocument(file, allowDuplicate);
        return ResponseEntity.ok(Map.of("message", "Document uploaded successfully.", "documentId", id, "status", "NOT_PROCESSED"));
    }



    @GetMapping("/{documentId}")
    public DocumentDetail getDocument(@PathVariable String documentId) {
        return documentService.getDocumentDetail(documentId);
    }
}
