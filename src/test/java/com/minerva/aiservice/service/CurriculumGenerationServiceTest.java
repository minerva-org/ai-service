package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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

    private DocumentTextExtractor documentTextExtractor;
    private OllamaClient ollamaClient;
    private ObjectMapper objectMapper;
    private CurriculumGenerationService service;

    @BeforeEach
    void setUp() {
        documentTextExtractor = mock(DocumentTextExtractor.class);
        ollamaClient = mock(OllamaClient.class);
        objectMapper = new ObjectMapper();
        service = new CurriculumGenerationService(
                documentTextExtractor,
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
                  "contenidos": [
                    {
                      "conceptoId": "CG-1",
                      "descripcion": "Incluye Sprint Backlog."
                    }
                  ],
                  "nodos": [
                    {
                      "tempId": "sprint-review",
                      "tipo": "concepto",
                      "nombre": "Sprint Review",
                      "parentId": "TG-1",
                      "contenido": [
                        {
                          "descripcion": "Inspecciona el Incremento."
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

        when(documentTextExtractor.extract(any(MultipartFile.class)))
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
        verify(ollamaClient).generateStructured(
                contains("cada unidad como una rama independiente"),
                contains("ruta="),
                anyMap());
    }

    @Test
    void createsNewUnitsWithTheirTopicsAndConceptsInOneResponse() throws Exception {
        JsonNode original = objectMapper.readTree("""
                {
                  "id": "MAT-1",
                  "tipo": "materia",
                  "nombre": "Programacion Orientada a Objetos",
                  "hijos": []
                }
                """);
        String generated = """
                {
                  "contenidos": [],
                  "nodos": [
                    {
                      "tempId": "concepto-1",
                      "tipo": "concepto",
                      "nombre": "Clase",
                      "parentId": "tema-1",
                      "contenido": [{"descripcion": "Plantilla para crear objetos."}]
                    },
                    {
                      "tempId": "tema-1",
                      "tipo": "tema",
                      "nombre": "Clases y objetos",
                      "parentId": "unidad-1"
                    },
                    {
                      "tempId": "unidad-1",
                      "tipo": "unidad",
                      "nombre": "Fundamentos de POO",
                      "parentId": "MAT-1"
                    },
                    {
                      "tempId": "unidad-2",
                      "tipo": "unidad",
                      "nombre": "Herencia",
                      "parentId": "MAT-1"
                    },
                    {
                      "tempId": "tema-2",
                      "tipo": "tema",
                      "nombre": "Jerarquia de herencia",
                      "parentId": "unidad-2"
                    },
                    {
                      "tempId": "concepto-2",
                      "tipo": "concepto",
                      "nombre": "Sobrescritura",
                      "parentId": "tema-2",
                      "contenido": [{"descripcion": "Redefine un metodo heredado."}]
                    }
                  ]
                }
                """;
        CurriculumGenerationRequest request = new CurriculumGenerationRequest(
                "GRP-1",
                original,
                List.of(new CurriculumGenerationRequest.FuenteNueva(
                        "DOC-102",
                        "def789",
                        "ProgramacionOrientadaAObjetos.pdf")),
                List.of(),
                "conservadora");
        when(documentTextExtractor.extract(any(MultipartFile.class)))
                .thenReturn("Escenario IV: Declarar una clase. Clases y objetos. "
                        + "Escenario V: Reutilizar codigo. Herencia y sobrescritura.");
        when(ollamaClient.generateStructured(anyString(), anyString(), anyMap()))
                .thenReturn(generated);

        CurriculumGenerationResponse response = service.generate(
                request,
                new MockMultipartFile[]{
                        new MockMultipartFile(
                                "files",
                                "ProgramacionOrientadaAObjetos.pdf",
                                "application/pdf",
                                new byte[]{1})});

        JsonNode units = response.arbol().get("hijos");
        assertEquals(2, units.size());
        assertEquals("Fundamentos de POO", units.get(0).get("nombre").asString());
        assertEquals("Clases y objetos",
                units.get(0).at("/hijos/0/nombre").asString());
        assertEquals("Clase",
                units.get(0).at("/hijos/0/hijos/0/nombre").asString());
        assertEquals("Plantilla para crear objetos.",
                units.get(0).at("/hijos/0/hijos/0/contenido/0/descripcion").asString());
        assertEquals("Herencia",
                units.get(1).get("nombre").asString());
        assertEquals("Sobrescritura",
                units.get(1).at("/hijos/0/hijos/0/nombre").asString());
        assertEquals("Redefine un metodo heredado.",
                units.get(1).at("/hijos/0/hijos/0/contenido/0/descripcion").asString());
    }

    @Test
    void mergesExistingConceptWhenNamesDifferOnlyByAccents() throws Exception {
        JsonNode original = objectMapper.readTree("""
                {
                  "tipo": "materia",
                  "nombre": "Matematicas",
                  "hijos": [
                    {
                      "tipo": "unidad",
                      "nombre": "Estructuras",
                      "hijos": [
                        {
                          "tipo": "tema",
                          "nombre": "Recursividad",
                          "hijos": [
                            {
                              "id": "FACTORIAL-1",
                              "tipo": "concepto",
                              "nombre": "Factorial",
                              "estadoConcepto": "sin_contenido",
                              "contenido": []
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
                  "contenidos": [
                    {
                      "conceptoId": "FACTORIAL-1",
                      "descripcion": "El factorial de cero es uno."
                    }
                  ],
                  "nodos": []
                }
                """;

        CurriculumGenerationRequest request = new CurriculumGenerationRequest(
                "GRP-1",
                original,
                List.of(new CurriculumGenerationRequest.FuenteNueva(
                        "DOC-1",
                        "checksum",
                        "recursividad.pdf")),
                List.of(),
                "conservadora");

        when(documentTextExtractor.extract(any(MultipartFile.class)))
                .thenReturn("El factorial de cero es uno.");
        when(ollamaClient.generateStructured(anyString(), anyString(), anyMap()))
                .thenReturn(generated);

        CurriculumGenerationResponse response = service.generate(
                request,
                new MockMultipartFile[]{
                        new MockMultipartFile(
                                "files",
                                "recursividad.pdf",
                                "application/pdf",
                                new byte[]{1})});

        JsonNode factorial = response.arbol()
                .at("/hijos/0/hijos/0/hijos/0");
        assertEquals("con_contenido", factorial.get("estadoConcepto").asString());
        assertEquals(1, factorial.at("/contenido").size());
        assertEquals("El factorial de cero es uno.",
                factorial.at("/contenido/0/descripcion").asString());
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
                  "contenidos": [],
                  "nodos": []
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

        when(documentTextExtractor.extract(any(MultipartFile.class)))
                .thenReturn("Materia didactica.");
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
