package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.minerva.aiservice.config.AiServiceProperties;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor(new AiServiceProperties(
            new AiServiceProperties.Ollama("http://localhost", "qwen3:8b", Duration.ofSeconds(180)),
            new AiServiceProperties.Security("a".repeat(32)),
            new AiServiceProperties.Limits(10485760, 30000)));

    @Test
    void extractsSelectableTextFromUploadedPdf() throws Exception {
        try (PDDocument pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 700);
                content.showText("Unidad Algebra");
                content.endText();
            }
            pdf.save(bytes);
            assertEquals("Unidad Algebra", extractor.extract(new MockMultipartFile(
                    "file", "plan.pdf", "application/pdf", bytes.toByteArray())));
        }
    }

    @Test
    void validatesNativePdfWithoutRequiringSelectableText() throws Exception {
        try (PDDocument pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(bytes);
            byte[] content = bytes.toByteArray();

            assertArrayEquals(content, extractor.validateAndRead(
                    new MockMultipartFile("file", "scanned-plan.pdf",
                            "application/pdf", content),
                    100));
        }
    }

    @Test
    void enforcesClaudeNativePdfPageLimit() throws Exception {
        try (PDDocument pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (int page = 0; page < 101; page++) {
                pdf.addPage(new PDPage());
            }
            pdf.save(bytes);

            assertThrows(PdfTextExtractor.PdfPageLimitException.class,
                    () -> extractor.validateAndRead(new MockMultipartFile(
                            "file", "plan.pdf", "application/pdf", bytes.toByteArray()), 100));
        }
    }

    // Input: PDF válido sin texto seleccionable.
    // Resultado esperado: NoExtractableTextException con mensaje de OCR no soportado.
    @Test
    void rejectsPdfWithoutSelectableText() throws Exception {
        try (PDDocument pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            pdf.save(bytes);

            var error = assertThrows(PdfTextExtractor.NoExtractableTextException.class,
                    () -> extractor.extract(new MockMultipartFile(
                            "file", "sin-texto.pdf", "application/pdf", bytes.toByteArray())));

            assertTrue(error.getMessage().contains("OCR"));
        }
    }

    @Test
    void rejectsExtractedTextThatExceedsConfiguredLimit() throws Exception {
        var limitedExtractor = new PdfTextExtractor(new AiServiceProperties(
                new AiServiceProperties.Ollama(
                        "http://localhost", "qwen3:8b", Duration.ofSeconds(180)),
                new AiServiceProperties.Security("a".repeat(32)),
                new AiServiceProperties.Limits(10485760, 10)));

        try (PDDocument pdf = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage());
            try (var content = new PDPageContentStream(
                    pdf, pdf.getPage(0))) {
                content.beginText();
                content.setFont(
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 700);
                content.showText("Texto demasiado largo");
                content.endText();
            }
            pdf.save(bytes);

            assertThrows(PdfTextExtractor.PdfTextTooLargeException.class,
                    () -> limitedExtractor.extract(new MockMultipartFile(
                            "file", "largo.pdf", "application/pdf",
                            bytes.toByteArray())));
        }
    }

    @Test
    void enforcesTenMiBBoundaryWithoutReadingOversizedUpload() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("plan.pdf");
        when(file.getSize()).thenReturn(10485760L);
        when(file.getBytes()).thenReturn(new byte[]{1});
        assertThrows(PdfTextExtractor.InvalidPdfException.class, () -> extractor.extract(file));
        clearInvocations(file);
        when(file.getSize()).thenReturn(10485761L);
        var error = assertThrows(ResponseStatusException.class, () -> extractor.extract(file));
        assertEquals(413, error.getStatusCode().value());
        verify(file, never()).getBytes();
    }
}
