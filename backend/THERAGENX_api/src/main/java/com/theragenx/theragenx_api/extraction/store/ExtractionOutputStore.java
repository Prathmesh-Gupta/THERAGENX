package com.theragenx.theragenx_api.extraction.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/** Stores the canonical extraction JSON files under the repository's out directory. */
@Component
public class ExtractionOutputStore {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private final ObjectMapper mapper;
    private final Path outputDirectory;

    public ExtractionOutputStore(ObjectMapper mapper,
            @Value("${app.output.path:../../out}") String outputPath) {
        this.mapper = mapper;
        this.outputDirectory = Path.of(outputPath).toAbsolutePath().normalize();
    }

    public synchronized void save(String documentId, JsonNode extraction) {
        Path target = resolve(documentId);
        Path temporary = null;
        try {
            Files.createDirectories(outputDirectory);
            temporary = Files.createTempFile(outputDirectory, documentId + "-", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), extraction);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save extraction output for " + documentId, exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public Optional<JsonNode> load(String documentId) {
        Path file = resolve(documentId);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(mapper.readTree(file.toFile()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read extraction output for " + documentId, exception);
        }
    }

    public boolean exists(String documentId) {
        return Files.isRegularFile(resolve(documentId));
    }

    public Path getOutputDirectory() { return outputDirectory; }

    private Path resolve(String documentId) {
        if (documentId == null || !SAFE_ID.matcher(documentId).matches()) {
            throw new IllegalArgumentException("Invalid document ID: " + documentId);
        }
        Path file = outputDirectory.resolve(documentId + ".json").normalize();
        if (!file.startsWith(outputDirectory)) throw new IllegalArgumentException("Invalid output path.");
        return file;
    }
}
