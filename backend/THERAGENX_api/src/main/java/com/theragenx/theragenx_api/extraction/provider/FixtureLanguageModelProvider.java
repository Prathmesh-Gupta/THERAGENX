package com.theragenx.theragenx_api.extraction.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theragenx.theragenx_api.extraction.fixtures.FixtureDocumentId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Offline provider: returns the recorded model text for the document in the prompt. */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "fixture")
public class FixtureLanguageModelProvider implements LanguageModelProvider {
    private final ObjectMapper mapper;
    private final Path fixtureDirectory;

    public FixtureLanguageModelProvider(ObjectMapper mapper,
                                        @Value("${app.fixtures.path:../../fixtures}") String fixturePath) {
        this.mapper = mapper;
        this.fixtureDirectory = Path.of(fixturePath).toAbsolutePath().normalize();
    }

    @Override public String providerName() { return "fixture"; }
    @Override public String modelName() { return "recorded-response"; }

    @Override
    public String generateJson(String prompt) {
        String documentId = FixtureDocumentId.fromPrompt(prompt);
        Path fixturePath = fixtureDirectory.resolve(documentId + ".json").normalize();
        if (!fixturePath.getParent().equals(fixtureDirectory)) {
            throw new IllegalArgumentException("Invalid fixture document ID.");
        }
        if (!Files.isRegularFile(fixturePath)) {
            throw new IllegalStateException("No recorded fixture found for " + documentId + " at " + fixturePath);
        }
        try {
            JsonNode fixture = mapper.readTree(fixturePath.toFile());
            JsonNode recordedPrompt = fixture.at("/request/prompt");
            JsonNode responseText = fixture.at("/response/text");
            if (responseText.isMissingNode() || !responseText.isTextual()) {
                throw new IllegalStateException("Fixture response.text is missing for " + documentId);
            }
            if (recordedPrompt.isTextual() && !recordedPrompt.asText().equals(prompt)) {
                // A changed catalog or prompt is allowed: replay uses the recorded response,
                // while the current Java validators re-check it against the current catalog.
            }
            return responseText.asText();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read fixture for " + documentId, exception);
        }
    }
}
