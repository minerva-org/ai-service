package com.minerva.aiservice.service;

import java.io.IOException;
import java.util.Arrays;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import com.minerva.aiservice.config.AiServiceProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PdfTextExtractor {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int SIGNATURE_SEARCH_LIMIT = 1024;

    private final AiServiceProperties properties;

    public String extract(byte[] content) {
        if (content == null || !hasPdfSignature(content)) {
            throw new InvalidPdfException("File content is not a PDF.");
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new InvalidPdfException("Encrypted PDFs are not supported.");
            }
            String text = new PDFTextStripper().getText(document).strip();
            if (text.isBlank()) {
                throw new NoExtractableTextException("PDF has no selectable text; OCR is not supported.");
            }
            if (text.length() > properties.limits().maxExtractedCharacters()) {
                throw new PdfTextTooLargeException("Extracted PDF text exceeds the configured limit.");
            }
            return text;
        } catch (IOException exception) {
            throw new InvalidPdfException("PDF could not be parsed.", exception);
        }
    }

    private static boolean hasPdfSignature(byte[] content) {
        int bytesToCheck = Math.min(content.length, SIGNATURE_SEARCH_LIMIT);
        for (int offset = 0; offset <= bytesToCheck - PDF_SIGNATURE.length; offset++) {
            if (Arrays.equals(content, offset, offset + PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length)) {
                return true;
            }
        }
        return false;
    }

    public static class InvalidPdfException extends RuntimeException {
        public InvalidPdfException(String message) {
            super(message);
        }

        public InvalidPdfException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class NoExtractableTextException extends RuntimeException {
        public NoExtractableTextException(String message) {
            super(message);
        }
    }

    public static class PdfTextTooLargeException extends RuntimeException {
        public PdfTextTooLargeException(String message) {
            super(message);
        }
    }
}
