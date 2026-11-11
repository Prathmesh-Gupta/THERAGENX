package com.theragenx.theragenx_api.fixtures;

import com.theragenx.theragenx_api.extraction.fixtures.FixtureDocumentId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureDocumentIdTest {
    @Test void extractsDocumentIdFromPrompt() {
        assertEquals("D07", FixtureDocumentId.fromPrompt("Header\nDocument ID: D07\nBody"));
    }
    @Test void rejectsPromptWithoutDocumentId() {
        assertThrows(IllegalArgumentException.class, () -> FixtureDocumentId.fromPrompt("no id"));
    }
}
