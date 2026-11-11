package com.theragenx.theragenx_api.extraction.provider;

public interface LanguageModelProvider {
    String providerName();
    String modelName();
    String generateJson(String prompt);
}
