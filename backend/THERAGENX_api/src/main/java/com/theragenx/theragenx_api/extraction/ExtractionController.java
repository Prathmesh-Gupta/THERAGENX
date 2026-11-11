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

    @PostMapping("/{documentId}")
    public ExtractionResult extractDocument(@PathVariable String documentId) {
        return extractionService.extract(documentId);
    }
}
