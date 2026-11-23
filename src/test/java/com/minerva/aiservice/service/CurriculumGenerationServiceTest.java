package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.CurriculumGenerationRequest;
import com.minerva.aiservice.dto.CurriculumGenerationResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CurriculumGenerationServiceTest {

    private PdfTextExtractor pdfTextExtractor;
    private OllamaClient ollamaClient;
    private ObjectMapper objectMapper;
    private CurriculumGenerationService service;

    @BeforeEach
    void setUp() {
        pdfTextExtractor = mock(PdfTextExtractor.class);
        ollamaClient = mock(OllamaClient.class);
        objectMapper = new ObjectMapper();
        service = new CurriculumGenerationService(
                pdfTextExtractor,
                ollamaClient,
                objectMapper);
    }

    @Test
    void preservesExistingNodesAndMarksGeneratedNodesAsIA() throws Exception {
        JsonNode original = objectMapper.readTree("""
                {
                  "id": "MAT-1",
                  "tipo": "materia",
                  "nombre": "Ingenieria de Software",
                  "origen": "materia",
                  "revisionEstado": "original",
                  "hijos": [
                    {
                      "id": "UG-1",
                      "tipo": "unidad",
                      "nombre": "Proceso de software",
                      "origen": "materia",
                      "revisionEstado": "original",
                      "hijos": [
                        {
                          "id": "TG-1",
                          "tipo": "tema",
                          "nombre": "Metodologias",
                          "origen": "materia",
                          "revisionEstado": "original",
                          "hijos": [
                            {
                              "id": "CG-1",
                              "tipo": "concepto",
                              "nombre": "Scrum",
                              "origen": "materia",
                              "revisionEstado": "original",
                              "estadoConcepto": "con_contenido",
                              "contenido": [
                                {
                                  "id": "CTG-1",
                                  "descripcion": "Marco agil.",
                                  "origen": "materia"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        String generated = """
                {
                  "id": "MAT-1",
                  "tipo": "materia",
                  "nombre": "Ingenieria de Software",
                  "origen": "materia",
                  "revisionEstado": "original",
                  "hijos": [
                    {
                      "id": "UG-1",
                      "tipo": "unidad",
                      "nombre": "Proceso de software",
                      "origen": "materia",
                      "revisionEstado": "original",
                      "hijos": [
                        {
                          "id": "TG-1",
                          "tipo": "tema",
                          "nombre": "Metodologias",
                          "origen": "materia",
                          "revisionEstado": "original",
                          "hijos": [
                            {
                              "id": "CG-1",
                              "tipo": "concepto",
                              "nombre": "Scrum",
                              "origen": "materia",
                              "revisionEstado": "original",
                              "estadoConcepto": "con_contenido",
                              "contenido": [
                                {
                                  "descripcion": "Incluye Sprint Backlog."
                                }
                              ]
                            },
                            {
                              "tipo": "concepto",
                              "nombre": "Sprint Review",
                              "contenido": [
                                {
                                  "descripcion": "Inspecciona el Incremento."
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """;

        CurriculumGenerationRequest request = new CurriculumGenerationRequest(
                "GRP-1",
                original,
                List.of(new CurriculumGenerationRequest.FuenteNueva(
                        "DOC-101",
                        "abc123",
                        "guia-scrum.pdf")),
                List.of(),
                "conservadora");

        when(pdfTextExtractor.extract(any(MultipartFile.class)))
                .thenReturn("Texto didactico sobre Scrum.");
        when(ollamaClient.generateStructured(anyString(), anyString(), anyMap()))
                .thenReturn(generated);

        CurriculumGenerationResponse response = service.generate(
                request,
                new MockMultipartFile[]{
                        new MockMultipartFile(
                                "files",
                                "guia-scrum.pdf",
                                "application/pdf",
                                new byte[]{1})});

        assertFalse(response.persistido());
        assertEquals("GRP-1", response.grupoId());
        assertEquals("CG-1",
                response.arbol().at("/hijos/0/hijos/0/hijos/0/id").asString());
        assertTrue(response.arbol().at("/hijos/0/hijos/0/hijos/1/nombre")
                .asString().equals("Sprint Review"));
        JsonNode newNode = response.arbol().at("/hijos/0/hijos/0/hijos/1");
        assertEquals("ia", newNode.get("origen").asString());
        assertEquals("pendiente_revision",
                newNode.get("revisionEstado").asString());
        assertEquals("procesada", response.fuentesResultado().get(0).estado());
    }

    @Test
    void rejectsAlreadyProcessedSourceWithoutCallingModel() {
        JsonNode original;
        try {
            original = objectMapper.readTree("""
                    {
                      "tipo": "materia",
                      "nombre": "Materia",
                      "hijos": []
                    }
                    """);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }

        CurriculumGenerationRequest request = new CurriculumGenerationRequest(
                "GRP-1",
                original,
                List.of(new CurriculumGenerationRequest.FuenteNueva(
                        "DOC-100",
                        "def456",
                        "fuente.pdf")),
                List.of(new CurriculumGenerationRequest.FuenteProcesada(
                        "DOC-100",
                        "def456")),
                "conservadora");

        CurriculumGenerationResponse response =
                service.generate(request, new MockMultipartFile[]{});

        assertEquals("ya_procesada",
                response.fuentesResultado().get(0).estado());
        assertFalse(response.persistido());
        verify(ollamaClient, never())
                .generateStructured(anyString(), anyString(), anyMap());
    }

    @Test
    void rejectsInvalidTemporaryTree() throws Exception {
        JsonNode invalid = objectMapper.readTree("""
                {"tipo":"materia"}
                """);
        CurriculumGenerationRequest request = new CurriculumGenerationRequest(
                "GRP-1",
                invalid,
                List.of(),
                List.of(),
                "conservadora");

        assertThrows(
                CurriculumGenerationService.InvalidCurriculumTreeException.class,
                () -> service.generate(request, null));
    }

    @Test
    void rejectsGeneratedTreeWithoutRelevantChanges() throws Exception {
        JsonNode original = objectMapper.readTree("""
                {
                  "tipo": "materia",
                  "nombre": "Materia",
                  "hijos": []
                }
                """);
        String generated = """
                {
                  "tipo": "materia",
                  "nombre": "Materia",
                  "hijos": []
                }
                """;

        CurriculumGenerationRequest request = new CurriculumGenerationRequest(
                "GRP-1",
                original,
                List.of(new CurriculumGenerationRequest.FuenteNueva(
                        "DOC-101",
                        "abc123",
                        "fuente.pdf")),
                List.of(),
                "conservadora");

        when(pdfTextExtractor.extract(any(MultipartFile.class)))
                .thenReturn("Texto didactico.");
        when(ollamaClient.generateStructured(anyString(), anyString(), anyMap()))
                .thenReturn(generated);

        assertThrows(
                CurriculumGenerationService.NoRelevantCurriculumException.class,
                () -> service.generate(
                        request,
                        new MockMultipartFile[]{
                                new MockMultipartFile(
                                        "files",
                                        "fuente.pdf",
                                        "application/pdf",
                                        new byte[]{1})}));
    }
}
