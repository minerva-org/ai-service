package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.time.Duration;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.minerva.aiservice.config.AiServiceProperties;

class DocumentTextExtractorTest {
    private final AiServiceProperties properties = new AiServiceProperties(
            new AiServiceProperties.Ollama(
                    "http://localhost", "qwen3:8b", Duration.ofSeconds(10)),
            new AiServiceProperties.Security("a".repeat(32)),
            new AiServiceProperties.Limits(1024 * 1024, 30000));
    private final PdfTextExtractor pdfExtractor = mock(PdfTextExtractor.class);
    private final DocumentTextExtractor extractor =
            new DocumentTextExtractor(pdfExtractor, properties);

    @Test
    void extractsTextFromDocx() throws Exception {
        byte[] content;
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Unidad 1: Álgebra");
            document.createParagraph().createRun().setText("Tema: Ecuaciones");
            document.write(output);
            content = output.toByteArray();
        }

        String text = extractor.extract(new MockMultipartFile(
                "file", "plan.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                content));

        assertTrue(text.contains("Unidad 1: Álgebra"));
        assertTrue(text.contains("Tema: Ecuaciones"));
    }

    @Test
    void extractsSlideTextAndKeepsSlideBoundariesFromPptx() throws Exception {
        byte[] content;
        try (XMLSlideShow presentation = new XMLSlideShow();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var firstSlide = presentation.createSlide();
            firstSlide.createTextBox().setText("Unidad 1: Fundamentos");
            var secondSlide = presentation.createSlide();
            secondSlide.createTextBox().setText("Unidad 2: Aplicaciones");
            var table = secondSlide.createTable();
            var row = table.addRow();
            row.addCell().setText("Concepto");
            row.addCell().setText("Método");
            presentation.write(output);
            content = output.toByteArray();
        }

        String text = extractor.extract(new MockMultipartFile(
                "file",
                "plan.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                content));

        assertTrue(text.contains("[DIAPOSITIVA 1]\nUnidad 1: Fundamentos"));
        assertTrue(text.contains("[DIAPOSITIVA 2]\nUnidad 2: Aplicaciones"));
        assertTrue(text.contains("Concepto"));
        assertTrue(text.contains("Método"));
    }

    @Test
    void rejectsLegacyDocAndDoesNotPassItToPdfExtractor() {
        assertThrows(ResponseStatusException.class, () -> extractor.extract(
                new MockMultipartFile("file", "plan.doc", "application/msword",
                        new byte[]{1, 2, 3})));

        verify(pdfExtractor, org.mockito.Mockito.never()).extract(
                org.mockito.ArgumentMatchers.any(
                        org.springframework.web.multipart.MultipartFile.class));
    }

    @Test
    void rejectsMalformedDocxAsAnInvalidDocument() {
        assertThrows(DocumentTextExtractor.InvalidDocumentException.class,
                () -> extractor.extract(new MockMultipartFile(
                        "file", "malformado.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        new byte[]{1, 2, 3})));
    }

    @Test
    void rejectsDocxExceedingConfiguredUploadLimitBeforeReading() throws Exception {
        var file = mock(org.springframework.web.multipart.MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("plan.docx");
        when(file.getSize()).thenReturn(1024L * 1024L + 1);

        assertThrows(ResponseStatusException.class, () -> extractor.extract(file));
        verify(file, org.mockito.Mockito.never()).getInputStream();
    }
}
