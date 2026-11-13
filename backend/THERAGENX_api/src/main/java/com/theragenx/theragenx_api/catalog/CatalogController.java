package com.theragenx.theragenx_api.catalog;

import com.theragenx.theragenx_api.catalog.model.FieldCatalog;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public FieldCatalog getCatalog() {
        return catalogService.getCatalog();
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadCatalog(
            @RequestParam("file") MultipartFile file
    ) {
        FieldCatalog catalog = catalogService.uploadCatalog(file);
        return ResponseEntity.ok(response("Catalog uploaded and activated successfully.", catalog));
    }

    @PutMapping
    public ResponseEntity<Map<String,Object>> saveCatalog(@RequestBody FieldCatalog catalog) {
        FieldCatalog saved = catalogService.saveCatalog(catalog);
        return ResponseEntity.ok(response("Catalog saved and activated successfully.", saved));
    }



    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadCatalog() {
        FieldCatalog catalog = catalogService.reloadCatalog();
        return ResponseEntity.ok(response("Catalog reloaded successfully.", catalog));
    }

    @GetMapping("/status")
    public Map<String, Object> getCatalogStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        FieldCatalog catalog = catalogService.getCatalog();
        status.put("loaded", true);
        status.put("catalogVersion", catalog.catalogVersion());
        status.put("fieldCount", catalog.fields().size());
        status.put("sectionCount", catalog.sections().size());
        status.put("loadedAt", catalogService.getLoadedAt());
        status.put("filePath", catalogService.getCatalogPath());
        return status;
    }

    private Map<String, Object> response(String message, FieldCatalog catalog) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("catalogVersion", catalog.catalogVersion());
        body.put("fieldCount", catalog.fields().size());
        body.put("sectionCount", catalog.sections().size());
        body.put("loadedAt", Instant.now());
        return body;
    }
}
