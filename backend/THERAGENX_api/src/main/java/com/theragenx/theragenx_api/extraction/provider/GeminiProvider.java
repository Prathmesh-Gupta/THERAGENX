package com.theragenx.theragenx_api.extraction.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "app.llm.provider",
        havingValue = "gemini"
)
public class GeminiProvider implements LanguageModelProvider {
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public GeminiProvider(
            ObjectMapper objectMapper,
            @Value("${app.gemini.api-key:}") String apiKey,
            @Value("${app.gemini.model:gemini-2.5-flash}") String model,
            @Value("${app.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String providerName() { return "gemini"; }
    public String modelName() { return model; }

    public String generateJson(String prompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API key is missing. Set GEMINI_API_KEY.");
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("responseMimeType", "application/json", "temperature", 0.1)
        );

        JsonNode response = restClient.post()
                .uri(uriBuilder -> uriBuilder.path("/models/{model}:generateContent")
                        .queryParam("key", apiKey).build(model))
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        JsonNode text = response == null ? null : response.at("/candidates/0/content/parts/0/text");
        if (text == null || text.isMissingNode() || text.isNull()) {
            throw new IllegalStateException("Gemini returned no text content.");
        }
        return text.asText();
    }
}
