package com.theragenx.theragenx_api.extraction;

import com.theragenx.theragenx_api.extraction.model.ExtractionResult;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/extractions")
public class ExtractionController {

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    // Normal extraction: uses saved output when the document is already processed.
    @PostMapping("/{documentId}")
    public ExtractionResult extractDocument(@PathVariable String documentId) {
        return extractionService.extract(documentId);
    }

    // Explicit re-extraction: invokes the configured LLM provider again.
    @PostMapping("/{documentId}/reprocess")
    public ExtractionResult reprocessDocument(@PathVariable String documentId) {
        return extractionService.reextract(documentId);
    }
}

