package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.minerva.aiservice.dto.DocumentExtractionResponse;

import jakarta.validation.Validation;
import tools.jackson.databind.ObjectMapper;

class DocumentExtractionServiceTest {

    private final DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
    private final PdfTextExtractor pdfExtractor = mock(PdfTextExtractor.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private DocumentExtractionService service;
    private final MockMultipartFile file = new MockMultipartFile(
            "file",
            "plan.pdf",
            "application/pdf",
            new byte[]{1});

    @BeforeEach
    void setUp() {
        service = new DocumentExtractionService(
                extractor,
                pdfExtractor,
                ollama,
                Validation.buildDefaultValidatorFactory().getValidator(),
                mapper);
        when(extractor.extract(file)).thenReturn("Unidad Algebra. Tema Ecuaciones.");
    }

    // Input: PDF con jerarquía válida.
    // Resultado esperado: devuelve unidad, tema y concepto sin propiedades adicionales.
    @Test
    void returnsValidatedHierarchy() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn("""
                {"units":[{"name":"Algebra","topics":[{"name":"Ecuaciones","concepts":[{"name":"Igualdad"}]}]}]}
                """);

        DocumentExtractionResponse result = service.extract(file);

        assertEquals(1, result.units().size());
        assertEquals("Algebra", result.units().get(0).name());
        assertEquals("Ecuaciones", result.units().get(0).topics().get(0).name());
        assertEquals("Igualdad",
                result.units().get(0).topics().get(0).concepts().get(0).name());
        verify(ollama).generateStructured(
                contains("unidad"),
                contains("Unidad Algebra"),
                eq(DocumentExtractionSchema.schema()));
    }

    @Test
    void preservesEveryUnitTopicAndConceptInTheGeneratedTree() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn("""
                {"units":[
                  {"name":"Unidad 1","topics":[
                    {"name":"Tema 1","concepts":[{"name":"Concepto 1"},{"name":"Concepto 2"}]},
                    {"name":"Tema 2","concepts":[{"name":"Concepto 3"}]}
                  ]},
                  {"name":"Unidad 2","topics":[
                    {"name":"Tema 3","concepts":[{"name":"Concepto 4"}]}
                  ]}
                ]}
                """);

        DocumentExtractionResponse result = service.extract(file);

        assertEquals(2, result.units().size());
        assertEquals(2, result.units().get(0).topics().size());
        assertEquals(2, result.units().get(0).topics().get(0).concepts().size());
        assertEquals("Concepto 4",
                result.units().get(1).topics().get(0).concepts().get(0).name());
        verify(ollama).generateStructured(anyString(), anyString(), anyMap());
    }

    @Test
    void givesClaudeTheOriginalPdfInsteadOfOnlyExtractedText() {
        byte[] pdf = new byte[]{1, 2, 3};
        when(ollama.supportsNativePdfInput()).thenReturn(true);
        when(pdfExtractor.validateAndRead(file, 100)).thenReturn(pdf);
        when(ollama.generateStructuredWithPdf(anyString(), anyString(), eq(pdf), anyMap()))
                .thenReturn("""
                        {"units":[{"name":"Algebra","topics":[{"name":"Ecuaciones","concepts":[{"name":"Igualdad"}]}]}]}
                        """);

        DocumentExtractionResponse result = service.extract(file);

        assertEquals(1, result.units().size());
        verify(ollama).generateStructuredWithPdf(
                contains("Actúa como docente"),
                contains("PDF adjunto completo"),
                eq(pdf),
                eq(DocumentExtractionSchema.schema()));
        verify(ollama, never()).generateStructured(anyString(), anyString(), anyMap());
        verify(extractor, never()).extract(file);
    }

    // Input: PDF que el modelo representa con units vacío.
    // Resultado esperado: devuelve error de ausencia de jerarquía académica utilizable.
    @Test
    void rejectsEmptyHierarchyAsUnrelatedOrUnusableContent() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap()))
                .thenReturn("{\"units\":[]}");

        assertThrows(DocumentExtractionService.NoUsableHierarchyException.class,
                () -> service.extract(file));
    }

    // Input: unidad sin temas.
    // Resultado esperado: rechaza la estructura incompleta.
    @Test
    void rejectsUnitWithoutTopics() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn(
                "{\"units\":[{\"name\":\"Unidad 1\",\"topics\":[]}]}");

        assertThrows(DocumentExtractionService.IncompleteHierarchyException.class,
                () -> service.extract(file));
    }

    // Input: tema sin conceptos.
    // Resultado esperado: rechaza la estructura incompleta.
    @Test
    void rejectsTopicWithoutConcepts() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn(
                "{\"units\":[{\"name\":\"Unidad 1\",\"topics\":[{\"name\":\"Tema 1\",\"concepts\":[]}]}]}");

        assertThrows(DocumentExtractionService.IncompleteHierarchyException.class,
                () -> service.extract(file));
    }

    // Input: nombre vacío dentro de un nodo.
    // Resultado esperado: rechaza la salida como inválida.
    @Test
    void rejectsBlankNodeName() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn(
                "{\"units\":[{\"name\":\" \",\"topics\":[{\"name\":\"Tema\",\"concepts\":[{\"name\":\"Concepto\"}]}]}]}");

        assertThrows(DocumentExtractionService.IncompleteHierarchyException.class,
                () -> service.extract(file));
    }

    // Input: JSON con texto adicional o campo no permitido.
    // Resultado esperado: rechaza en lugar de repararlo.
    @Test
    void rejectsTrailingOrExtraJson() {
        List<String> invalidOutputs = List.of(
                "{\"units\":[]} texto extra",
                "{\"units\":[],\"explanation\":\"extra\"}",
                "{\"units\":[{\"name\":\"Unidad\",\"topics\":[{\"name\":\"Tema\",\"concepts\":[{\"name\":\"Concepto\",\"extra\":true}]}]}]}");

        for (String output : invalidOutputs) {
            when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn(output);
            assertThrows(RuntimeException.class,
                    () -> service.extract(file),
                    output);
        }
    }
}
