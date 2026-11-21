package com.minerva.aiservice.controller;

import static org.mockito.ArgumentMatchers.any;
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
import com.minerva.aiservice.dto.DocumentExtractionResponse;
import com.minerva.aiservice.security.ServiceKeyFilter;
import com.minerva.aiservice.service.DocumentExtractionService;
import com.minerva.aiservice.service.OllamaClient;

class DocumentControllerTest {

    private final DocumentExtractionService service = mock(DocumentExtractionService.class);
    private final String key = "a".repeat(32);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var props = new AiServiceProperties(
                new AiServiceProperties.Ollama("http://localhost", "qwen3:8b", Duration.ofSeconds(180)),
                new AiServiceProperties.Security(key),
                new AiServiceProperties.Limits(10485760, 30000));
        mvc = MockMvcBuilders.standaloneSetup(new DocumentController(service))
                .setControllerAdvice(new AiExceptionHandler())
                .addFilters(new ServiceKeyFilter(props))
                .build();
    }

    // Input: PDF autorizado con jerarquía válida.
    // Resultado esperado: HTTP 200 y unidad-tema-concepto.
    @Test
    void returns200ForUsableHierarchy() throws Exception {
        DocumentExtractionResponse result = new DocumentExtractionResponse(List.of(
                new DocumentExtractionResponse.UnitDTO("Unidad",
                        List.of(new DocumentExtractionResponse.TopicDTO("Tema",
                                List.of(new DocumentExtractionResponse.ConceptDTO("Concepto")))))));
        when(service.extract(any())).thenReturn(result);

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "plan.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0].name").value("Unidad"))
                .andExpect(jsonPath("$.units[0].topics[0].concepts[0].name").value("Concepto"));
    }

    // Input: documento sin jerarquía académica utilizable.
    // Resultado esperado: HTTP 422 con mensaje identificable.
    @Test
    void returns422ForNoUsableHierarchy() throws Exception {
        when(service.extract(any())).thenThrow(
                new DocumentExtractionService.NoUsableHierarchyException(
                        "No se identifico contenido academico utilizable."));

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "recibo.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.message").value(
                        "No se identifico contenido academico utilizable."));
    }

    // Input: modelo devuelve unidad sin temas.
    // Resultado esperado: HTTP 422 con código INCOMPLETE_HIERARCHY y detalles.
    @Test
    void returns422ForIncompleteHierarchy() throws Exception {
        when(service.extract(any())).thenThrow(
                new DocumentExtractionService.IncompleteHierarchyException(
                        List.of("$.units[0].topics: la lista no puede estar vacia.")));

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "plan.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INCOMPLETE_HIERARCHY"))
                .andExpect(jsonPath("$.validationErrors[0]").value(
                        "$.units[0].topics: la lista no puede estar vacia."));
    }

    // Input: salida inválida del modelo.
    // Resultado esperado: HTTP 502 sin exponer contenido crudo.
    @Test
    void returns502ForInvalidModelResponse() throws Exception {
        when(service.extract(any())).thenThrow(
                new OllamaClient.InvalidModelResponseException(
                        "Model output did not match the document hierarchy schema."));

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "plan.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(
                        "La respuesta del modelo no pudo validarse."));
    }

    // Input: Ollama excede el timeout configurado.
    // Resultado esperado: HTTP 504 con código OLLAMA_TIMEOUT.
    @Test
    void returns504ForOllamaTimeout() throws Exception {
        when(service.extract(any())).thenThrow(
                new OllamaClient.OllamaTimeoutException(
                        new java.net.http.HttpTimeoutException("Request cancelled")));

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "plan.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("OLLAMA_TIMEOUT"))
                .andExpect(jsonPath("$.message").value(
                        "La generacion excedio el tiempo de espera."));
    }

    // Input: una petición con 422 seguida de una petición válida.
    // Resultado esperado: ambas responden; el servicio continúa activo.
    @Test
    void remainsAvailableAfterHandledExtractionError() throws Exception {
        DocumentExtractionResponse valid = new DocumentExtractionResponse(List.of(
                new DocumentExtractionResponse.UnitDTO("Unidad",
                        List.of(new DocumentExtractionResponse.TopicDTO("Tema",
                                List.of(new DocumentExtractionResponse.ConceptDTO("Concepto")))))));
        when(service.extract(any()))
                .thenThrow(new DocumentExtractionService.NoUsableHierarchyException(
                        "No se identifico contenido academico utilizable."))
                .thenReturn(valid);

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "recibo.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "plan.pdf",
                                "application/pdf", new byte[]{1}))
                        .header("X-AI-Service-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.units[0].name").value("Unidad"));
    }

    // Input: petición sin clave interna.
    // Resultado esperado: 401 y no se invoca el servicio.
    @Test
    void rejectsMissingInternalKey() throws Exception {
        mvc.perform(multipart("/documents/extract")
                        .file(new MockMultipartFile("file", "plan.pdf",
                                "application/pdf", new byte[]{1})))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
