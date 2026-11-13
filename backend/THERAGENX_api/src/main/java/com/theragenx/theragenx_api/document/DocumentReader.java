package com.theragenx.theragenx_api.document;

import com.theragenx.theragenx_api.document.model.DocumentContent;
import com.theragenx.theragenx_api.document.model.DocumentSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

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


    public synchronized Map<String, Object> checkDuplicate(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("Please choose a non-empty PDF or TXT document.");
        }

        String uploadedText = extractUploadedText(upload);
        String uploadedTextHash = sha256NormalizedText(uploadedText);
        String uploadedFileHash;
        try {
            uploadedFileHash = sha256Bytes(upload.getBytes());
            Files.createDirectories(documentsPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to access the uploaded document or documents directory.", ex);
        }

        try (Stream<Path> files = Files.list(documentsPath)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return fileName.endsWith(".txt") || fileName.endsWith(".pdf");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {

                String existingName = file.getFileName().toString();
                String existingId = existingName.substring(0, existingName.lastIndexOf('.'));

                // First compare the original bytes. This catches an identical PDF even
                // when text extraction or whitespace differs between PDF readers.
                if (sha256Bytes(Files.readAllBytes(file)).equals(uploadedFileHash)) {
                    return duplicateResult(existingId, existingName,
                            "This file is an exact duplicate of an existing document.");
                }

                // Then compare normalized text across BOTH TXT and selectable-text PDFs.
                // This catches the same report uploaded once as TXT and once as PDF.
                try {
                    String existingText;
                    if (existingName.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                        existingText = Files.readString(file, StandardCharsets.UTF_8);
                    } else {
                        try (var pdf = Loader.loadPDF(file.toFile())) {
                            existingText = new PDFTextStripper().getText(pdf);
                        }
                    }

                    if (existingText != null && !existingText.isBlank()
                            && sha256NormalizedText(existingText).equals(uploadedTextHash)) {
                        return duplicateResult(existingId, existingName,
                                "This document has the same normalized text as an existing document.");
                    }
                } catch (Exception unreadableExistingFile) {
                    // An unreadable existing PDF cannot be compared by text, but its
                    // original-file hash was already checked above. Continue safely.
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to check existing documents.", ex);
        }

        return Map.of("duplicate", false, "message", "No duplicate document was found.");
    }

    private static Map<String, Object> duplicateResult(String documentId, String fileName, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("duplicate", true);
        result.put("existingDocumentId", documentId);
        result.put("existingFileName", fileName);
        result.put("message", message);
        return result;
    }

    public synchronized String uploadDocument(MultipartFile upload, boolean allowDuplicate) {
        String content = extractUploadedText(upload);
        Map<String,Object> duplicate = checkDuplicate(upload);
        if (Boolean.TRUE.equals(duplicate.get("duplicate")) && !allowDuplicate) {
            throw new IllegalStateException("Duplicate confirmation is required before saving this document.");
        }
        String originalName = upload.getOriginalFilename() == null ? "document" : upload.getOriginalFilename();
        String lower = originalName.toLowerCase(Locale.ROOT);
        String base = originalName.replaceAll("(?i)\\.(pdf|txt)$", "").replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        if (base.isBlank()) base = "document";
        try {
            Files.createDirectories(documentsPath);
            String id = base; int suffix = 2;
            while (Files.exists(documentsPath.resolve(id + ".txt"))) id = base + "_" + suffix++;
            Files.writeString(documentsPath.resolve(id + ".txt"), content, StandardCharsets.UTF_8);
            if (lower.endsWith(".pdf")) Files.copy(upload.getInputStream(), documentsPath.resolve(id + ".pdf"));
            return id;
        } catch (IOException ex) { throw new IllegalStateException("Unable to save uploaded document.", ex); }
    }

    private String extractUploadedText(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) throw new IllegalArgumentException("Please choose a non-empty PDF or TXT document.");
        String name = upload.getOriginalFilename() == null ? "" : upload.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".txt")) {
            try { String text = new String(upload.getBytes(), StandardCharsets.UTF_8); if (text.isBlank()) throw new IllegalArgumentException("The TXT file contains no readable text."); return text; }
            catch (IOException ex) { throw new IllegalStateException("Unable to read uploaded TXT file.", ex); }
        }
        if (name.endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(upload.getBytes())) { String text = new PDFTextStripper().getText(pdf); if (text == null || text.isBlank()) throw new IllegalArgumentException("No selectable text found. Scanned PDFs require OCR."); return text; }
            catch (IllegalArgumentException ex) { throw ex; } catch (Exception ex) { throw new IllegalArgumentException("Unable to read uploaded PDF.", ex); }
        }
        throw new IllegalArgumentException("Only PDF and TXT files are supported.");
    }

    private static String sha256NormalizedText(String text) {
        String normalized = java.text.Normalizer.normalize(text == null ? "" : text, java.text.Normalizer.Form.NFC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("\\s+", " ")
                .strip()
                .toLowerCase(Locale.ROOT);
        return sha256Bytes(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Bytes(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }


    public String getDocumentsPath() {
        return documentsPath.toString();
    }
}
