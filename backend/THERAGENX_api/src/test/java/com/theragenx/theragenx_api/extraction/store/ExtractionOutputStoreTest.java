package com.theragenx.theragenx_api.extraction.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ExtractionOutputStoreTest {
    @TempDir Path tempDir;

    @Test void savesAndLoadsJsonOutput() {
        ExtractionOutputStore store = new ExtractionOutputStore(new ObjectMapper(), tempDir.toString());
        var output = JsonNodeFactory.instance.objectNode();
        output.put("document_id", "D01");
        output.putArray("cases");
        store.save("D01", output);
        assertTrue(store.exists("D01"));
        assertEquals("D01", store.load("D01").orElseThrow().path("document_id").asText());
    }

    @Test void returnsEmptyWhenOutputDoesNotExist() {
        ExtractionOutputStore store = new ExtractionOutputStore(new ObjectMapper(), tempDir.toString());
        assertTrue(store.load("D02").isEmpty());
    }

    @Test void rejectsUnsafeDocumentId() {
        ExtractionOutputStore store = new ExtractionOutputStore(new ObjectMapper(), tempDir.toString());
        assertThrows(IllegalArgumentException.class, () -> store.load("../secrets"));
    }
}
