package com.theragenx.theragenx_api.extraction.validation;

import java.util.List;

public record ValidationReport(List<String> errors) {
    public ValidationReport {
        errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }
}
