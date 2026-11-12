package com.theragenx.theragenx_api.extraction.duplicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent exact-content processing state, keyed by SHA-256. */
@Service
public class DocumentDuplicateService {
    public enum State { NEW, IN_PROGRESS, COMPLETED, FAILED }

    public record Check(State state, String originalDocumentId) {}

    private record Entry(String documentId, State state) {}

    private final ObjectMapper mapper;
    private final Path indexPath;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public DocumentDuplicateService(ObjectMapper mapper,
            @Value("${app.duplicate.index-path:../../outputs/document-hash-index.json}") String indexPath) {
        this.mapper = mapper;
        this.indexPath = Path.of(indexPath).toAbsolutePath().normalize();
        loadIndex();
    }

    /** Atomically checks the content and claims it for processing. */

    public synchronized Check beginProcessing(String documentId, String content) {
        String hash = sha256(content == null ? "" : content);
        Entry existing = entries.get(hash);

        if (existing != null && existing.state() == State.COMPLETED) {
            // Same document requested again: reuse its saved output.
            if (existing.documentId().equals(documentId)) {
                return new Check(State.COMPLETED, null);
            }

            // Different document with identical content: genuine duplicate.
            return new Check(State.COMPLETED, existing.documentId());
        }

        if (existing != null && existing.state() == State.IN_PROGRESS) {
            if (existing.documentId().equals(documentId)) {
                return new Check(State.IN_PROGRESS, null);
            }

            return new Check(State.IN_PROGRESS, existing.documentId());
        }

        entries.put(hash, new Entry(documentId, State.IN_PROGRESS));

        try {
            persistIndex();
        } catch (RuntimeException exception) {
            if (existing == null) entries.remove(hash);
            else entries.put(hash, existing);
            throw exception;
        }

        return new Check(State.NEW, null);
    }



    /**
     * Explicitly claims a previously processed document for re-extraction.
     * Only the same source document can force a reprocessing operation.
     */
    public synchronized Check beginReprocessing(
            String documentId, String content) {

        String hash = sha256(content == null ? "" : content);

        Entry existing = entries.get(hash);

        if (existing != null
                && existing.state() == State.IN_PROGRESS) {
            return new Check(State.IN_PROGRESS, existing.documentId());
        }

        // Do not allow a document to bypass duplicate detection
        // for content belonging to a different document.
        if (existing != null
                && !existing.documentId().equals(documentId)
                && existing.state() == State.COMPLETED) {

            return new Check(State.COMPLETED, existing.documentId());
        }

        entries.put(hash, new Entry(documentId, State.IN_PROGRESS));

        try {
            persistIndex();
        } catch (RuntimeException exception) {
            if (existing == null) {
                entries.remove(hash);
            } else {
                entries.put(hash, existing);
            }
            throw exception;
        }

        return new Check(State.NEW, null);
    }


    public synchronized void markCompleted(String documentId, String content) {
        String hash = sha256(content == null ? "" : content);
        Entry existing = entries.get(hash);
        entries.put(hash, new Entry(documentId, State.COMPLETED));
        try {
            persistIndex();
        } catch (RuntimeException exception) {
            if (existing == null) entries.remove(hash);
            else entries.put(hash, existing);
            throw exception;
        }
    }

    public synchronized void markFailed(String documentId, String content) {
        String hash = sha256(content == null ? "" : content);
        Entry existing = entries.get(hash);
        if (existing != null && existing.documentId().equals(documentId)
                && existing.state() == State.IN_PROGRESS) {
            entries.put(hash, new Entry(documentId, State.FAILED));
            persistIndex();
        }
    }

    private void loadIndex() {
        if (!Files.isRegularFile(indexPath)) return;
        try {
            JsonNode loaded = mapper.readTree(indexPath.toFile());
            if (loaded == null || !loaded.isObject()) return;
            loaded.fields().forEachRemaining(item -> {
                JsonNode value = item.getValue();
                if (value.isTextual()) { // Migrate the original hash -> document ID format.
                    entries.put(item.getKey(), new Entry(value.asText(), State.COMPLETED));
                } else if (value.isObject()) {
                    String id = value.path("documentId").asText("");
                    State state;
                    try {
                        state = State.valueOf(value.path("state").asText("FAILED"));
                    } catch (IllegalArgumentException exception) {
                        state = State.NEW;
                    }
                    if (!id.isBlank() && state != State.NEW) {
                        // A process cannot still be active after application restart; allow its retry.
                        // A process cannot still be active after application restart; allow its retry.
                        // This code runs inside a lambda, so do not use loop control statements here.
                        State recoveredState = state == State.IN_PROGRESS ? State.FAILED : state;
                        entries.put(item.getKey(), new Entry(id, recoveredState));
                    }
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read document duplicate index: " + indexPath, exception);
        }
    }

    private void persistIndex() {
        Path temporaryPath = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        try {
            Files.createDirectories(indexPath.getParent());
            ObjectNode root = mapper.createObjectNode();
            entries.forEach((hash, entry) -> {
                ObjectNode value = root.putObject(hash);
                value.put("documentId", entry.documentId());
                value.put("state", entry.state().name());
            });
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporaryPath.toFile(), root);
            try {
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveException) {
                Files.move(temporaryPath, indexPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save document duplicate index: " + indexPath, exception);
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
