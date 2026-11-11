package com.theragenx.theragenx_api.document;

import com.theragenx.theragenx_api.document.model.DocumentContent;
import com.theragenx.theragenx_api.document.model.DocumentSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
public class DocumentReader {

    private static final Pattern SAFE_DOCUMENT_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final Path documentsPath;

    public DocumentReader(
            @Value("${app.documents.path:../../documents}") String documentsPath
    ) {
        this.documentsPath = Path.of(documentsPath).toAbsolutePath().normalize();
    }

    public List<DocumentSummary> getAllDocuments() {
        if (!Files.isDirectory(documentsPath)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(documentsPath)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::toSummary)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read documents directory: " + documentsPath, exception);
        }
    }

    public DocumentContent getDocument(String documentId) {
        if (documentId == null || !SAFE_DOCUMENT_ID.matcher(documentId).matches()) {
            throw new IllegalArgumentException("Invalid document ID: " + documentId);
        }

        Path file = documentsPath.resolve(documentId + ".txt").normalize();

        if (!file.startsWith(documentsPath)) {
            throw new IllegalArgumentException("Invalid document path.");
        }

        if (!Files.isRegularFile(file)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Document not found: " + documentId);
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return new DocumentContent(
                    documentId,
                    file.getFileName().toString(),
                    "txt",
                    content.length(),
                    content
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read document: " + file.getFileName(), exception);
        }
    }

    private DocumentSummary toSummary(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String fileName = file.getFileName().toString();
            String id = fileName.substring(0, fileName.length() - 4);

            return new DocumentSummary(id, fileName, "txt", content.length());
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read document: " + file.getFileName(), exception);
        }
    }

    public String getDocumentsPath() {
        return documentsPath.toString();
    }
}
