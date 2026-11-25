package com.minerva.aiservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.minerva.aiservice.config.AiServiceProperties;
import com.minerva.aiservice.dto.CurriculumGenerationResponse;
import com.minerva.aiservice.security.ServiceKeyFilter;
import com.minerva.aiservice.service.CurriculumGenerationService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class CurriculumGenerationControllerTest {

    private final CurriculumGenerationService service =
            mock(CurriculumGenerationService.class);
    private final String key = "b".repeat(32);
    private MockMvc mvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        var props = new AiServiceProperties(
                new AiServiceProperties.Ollama(
                        "http://localhost",
                        "qwen3:8b",
                        Duration.ofSeconds(180)),
                new AiServiceProperties.Security(key),
                new AiServiceProperties.Limits(10485760, 30000, 4000000));
        mvc = MockMvcBuilders.standaloneSetup(
                        new CurriculumGenerationController(service))
                .setControllerAdvice(new AiExceptionHandler())
                .addFilters(new ServiceKeyFilter(props))
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    void returnsTemporaryCurriculumWithoutPersistence() throws Exception {
        ObjectNode arbol = objectMapper.createObjectNode();
        arbol.put("tipo", "materia");
        arbol.put("nombre", "Materia");
        arbol.putArray("hijos");

        CurriculumGenerationResponse response =
                new CurriculumGenerationResponse(
                        "GRP-1",
                        false,
                        arbol,
                        List.of(new CurriculumGenerationResponse.FuenteResultado(
                                "DOC-101",
                                "abc123",
                                "procesada",
                                1,
                                2,
                                null)),
                        List.of(new CurriculumGenerationResponse.FuenteProcesada(
                                "DOC-101",
                                "abc123")),
                        List.of(),
                        List.of());

        when(service.generate(any(), any()))
                .thenReturn(response);

        String requestJson = """
                {
                  "grupoId": "GRP-1",
                  "arbol": {
                    "tipo": "materia",
                    "nombre": "Materia",
                    "hijos": []
                  },
                  "fuentes": [
                    {
                      "documentoId": "DOC-101",
                      "checksumSha256": "abc123",
                      "nombre": "fuente.pdf"
                    }
                  ],
                  "fuentesProcesadas": [],
                  "modoFusion": "conservadora"
                }
                """;

        mvc.perform(multipart("/curriculum/generate")
                        .file(new MockMultipartFile(
                                "request",
                                "request.json",
                                "application/json",
                                requestJson.getBytes()))
                        .file(new MockMultipartFile(
                                "files",
                                "fuente.pdf",
                                "application/pdf",
                                new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grupoId").value("GRP-1"))
                .andExpect(jsonPath("$.persistido").value(false))
                .andExpect(jsonPath("$.fuentesResultado[0].estado").value("procesada"));
    }

    @Test
    void rejectsMissingInternalKey() throws Exception {
        String requestJson = """
                {
                  "grupoId": "GRP-1",
                  "arbol": {"tipo":"materia","nombre":"Materia","hijos":[]},
                  "fuentes": [],
                  "fuentesProcesadas": [],
                  "modoFusion": "conservadora"
                }
                """;

        mvc.perform(multipart("/curriculum/generate")
                        .file(new MockMultipartFile(
                                "request",
                                "request.json",
                                "application/json",
                                requestJson.getBytes())))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidRequest() throws Exception {
        mvc.perform(multipart("/curriculum/generate")
                        .file(new MockMultipartFile(
                                "request",
                                "request.json",
                                "application/json",
                                "{}".getBytes()))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
