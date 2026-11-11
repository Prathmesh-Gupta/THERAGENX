package com.theragenx.theragenx_api.extraction.fixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores a sanitized model exchange. The API key is never part of this record. */
@Component
public class FixtureRecorder {
    private final ObjectMapper mapper;
    private final Path fixtureDirectory;

    public FixtureRecorder(ObjectMapper mapper,
                           @Value("${app.fixtures.path:../../fixtures}") String fixturePath) {
        this.mapper = mapper;
        this.fixtureDirectory = Path.of(fixturePath).toAbsolutePath().normalize();
    }

    public void record(String documentId, String provider, String model, String prompt, String response) {
        if (documentId == null || !documentId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid document ID for fixture recording.");
        }
        try {
            Files.createDirectories(fixtureDirectory);
            ObjectNode fixture = mapper.createObjectNode();
            fixture.put("documentId", documentId);
            fixture.put("provider", provider);
            fixture.put("model", model);
            fixture.putObject("request").put("prompt", prompt);
            fixture.putObject("response").put("text", response);

            Path target = fixtureDirectory.resolve(documentId + ".json");
            Path temporary = Files.createTempFile(fixtureDirectory, documentId, ".tmp");
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), fixture);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicMoveNotSupported) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not record model fixture for " + documentId, exception);
        }
    }
}
