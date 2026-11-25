package com.minerva.aiservice.service;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.minerva.aiservice.config.AiServiceProperties;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PdfTextExtractor {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int SIGNATURE_SEARCH_LIMIT = 1024;
    private static final Logger LOGGER =
            Logger.getLogger(PdfTextExtractor.class.getName());

    private final AiServiceProperties properties;

    public String extract(MultipartFile file) {
        return extract(readPdfUpload(file));
    }

    public byte[] validateAndRead(MultipartFile file, int maxPages) {
        if (maxPages <= 0) {
            throw new IllegalArgumentException("maxPages must be positive.");
        }
        byte[] content = readPdfUpload(file);
        if (!hasPdfSignature(content)) {
            throw new InvalidPdfException("File content is not a PDF.");
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new InvalidPdfException("Encrypted PDFs are not supported.");
            }
            if (document.getNumberOfPages() > maxPages) {
                throw new PdfPageLimitException(
                        "PDF exceeds the maximum page count for native model input.");
            }
            return content;
        } catch (IOException exception) {
            throw new InvalidPdfException("PDF could not be parsed.", exception);
        }
    }

    private byte[] readPdfUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a PDF file.");
        }
        if (file.getSize() > properties.limits().maxPdfBytes()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(413), "PDF exceeds the configured size limit.");
        }
        if (file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF files are accepted.");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded PDF.", exception);
        }
    }

    public String extract(byte[] content) {
        if (content == null || !hasPdfSignature(content)) {
            throw new InvalidPdfException("File content is not a PDF.");
        }

        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new InvalidPdfException("Encrypted PDFs are not supported.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setPageStart("\n[DIAPOSITIVA]\n");
            String text = stripper.getText(document).strip();
            LOGGER.log(Level.INFO,
                    "EXTRACCION_PDF: pdfs={0}, paginas={1}, caracteresExtraidos={2}",
                    new Object[]{1, document.getNumberOfPages(), text.length()});
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

    public static class PdfPageLimitException extends RuntimeException {
        public PdfPageLimitException(String message) {
            super(message);
        }
    }
}
