package com.theragenx.theragenx_api.extraction.validation;

import java.util.List;

public record CaseValidationResult(boolean validCase, List<String> errors) {
    public CaseValidationResult {
        errors = List.copyOf(errors);
    }
}
