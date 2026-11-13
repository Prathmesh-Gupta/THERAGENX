package com.theragenx.theragenx_api.catalog;

import com.theragenx.theragenx_api.catalog.model.FieldCatalog;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;

@Service
public class CatalogService {

    private final CatalogReader catalogReader;

    public CatalogService(CatalogReader catalogReader) {
        this.catalogReader = catalogReader;
    }

    public FieldCatalog getCatalog() {
        return catalogReader.getCatalog();
    }

    public FieldCatalog uploadCatalog(MultipartFile file) {
        return catalogReader.uploadCatalog(file);
    }

    public FieldCatalog saveCatalog(FieldCatalog catalog) { return catalogReader.saveCatalog(catalog); }



    public FieldCatalog reloadCatalog() {
        return catalogReader.reloadCatalog();
    }

    public Instant getLoadedAt() {
        return catalogReader.getLoadedAt();
    }

    public String getCatalogPath() {
        return catalogReader.getCatalogPath().toString();
    }
}
