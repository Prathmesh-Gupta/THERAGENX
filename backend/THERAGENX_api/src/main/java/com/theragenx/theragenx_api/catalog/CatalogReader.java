package com.theragenx.theragenx_api.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.theragenx.theragenx_api.catalog.model.FieldCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class CatalogReader {

    private final ObjectMapper objectMapper;
    private final Path catalogPath;
    private final AtomicReference<FieldCatalog> activeCatalog = new AtomicReference<>();
    private volatile Instant loadedAt;

    public CatalogReader(
            ObjectMapper objectMapper,
            @Value("${app.catalog.path:../../catalog/field_catalog.json}") String catalogPath
    ) {
        this.objectMapper = objectMapper;
        this.catalogPath = Path.of(catalogPath).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void loadCatalog() {
        reloadCatalog();
    }

    public synchronized FieldCatalog reloadCatalog() {
        try {
            FieldCatalog candidate = readAndValidate(catalogPath);
            activeCatalog.set(candidate);
            loadedAt = Instant.now();
            System.out.println("THERAGENX catalog loaded. Fields: " + candidate.fields().size()
                    + ", version: " + candidate.catalogVersion());
            return candidate;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load catalog from: " + catalogPath, exception);
        }
    }

    public synchronized FieldCatalog uploadCatalog(org.springframework.web.multipart.MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Please upload a non-empty JSON catalog file.");
        }

        Path temporaryFile = null;
        try {
            Path parent = catalogPath.getParent();
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, "catalog-upload-", ".json");
            file.transferTo(temporaryFile);

            // Validate the uploaded file before replacing the current catalog.
            FieldCatalog candidate = readAndValidate(temporaryFile);

            // Replace the on-disk catalog only after validation succeeds.
            try {
                Files.move(temporaryFile, catalogPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, catalogPath, StandardCopyOption.REPLACE_EXISTING);
            }

            activeCatalog.set(candidate);
            loadedAt = Instant.now();
            System.out.println("THERAGENX catalog uploaded and activated. Fields: "
                    + candidate.fields().size() + ", version: " + candidate.catalogVersion());
            return candidate;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to upload catalog file.", exception);
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // Temporary-file cleanup is best effort.
                }
            }
        }
    }

    private FieldCatalog readAndValidate(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalStateException("Catalog file not found: " + path);
        }

        FieldCatalog catalog = objectMapper.readValue(path.toFile(), FieldCatalog.class);

        if (catalog == null || catalog.sections() == null || catalog.fields() == null) {
            throw new IllegalArgumentException(
                    "Invalid catalog: sections and fields must be present.");
        }

        if (catalog.fields().isEmpty()) {
            throw new IllegalArgumentException("Invalid catalog: fields cannot be empty.");
        }

        for (var field : catalog.fields()) {
            if (field.id() == null || field.id().isBlank()
                    || field.label() == null || field.label().isBlank()
                    || field.section() == null || field.section().isBlank()
                    || field.type() == null || field.type().isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid catalog: every field must have id, label, section and type.");
            }
        }

        return catalog;
    }

    public FieldCatalog getCatalog() {
        FieldCatalog catalog = activeCatalog.get();
        if (catalog == null) {
            throw new IllegalStateException("Catalog has not been loaded.");
        }
        return catalog;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    public Path getCatalogPath() {
        return catalogPath;
    }
}
