package com.minerva.aiservice.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.minerva.aiservice.config.AiServiceProperties;
import com.minerva.aiservice.dto.DocumentLabResponse;
import com.minerva.aiservice.security.ServiceKeyFilter;
import com.minerva.aiservice.service.DocumentLaboratoryService;

class DocumentLaboratoryControllerTest {

    private final DocumentLaboratoryService service = mock(DocumentLaboratoryService.class);
    private final String key = "a".repeat(32);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var props = new AiServiceProperties(
                new AiServiceProperties.Ollama("http://localhost", "qwen3:8b", Duration.ofSeconds(180)),
                new AiServiceProperties.Security(key), new AiServiceProperties.Limits(10485760, 30000));
        mvc = MockMvcBuilders.standaloneSetup(new DocumentLaboratoryController(service))
                .setControllerAdvice(new AiExceptionHandler())
                .addFilters(new ServiceKeyFilter(props)).build();
    }

    @Test
    void requiresInternalKeyForPromptDefaults() throws Exception {
        mvc.perform(get("/laboratory/documents/prompts")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void rejectsBlankPromptsAndMissingSourceBeforeCallingModel() throws Exception {
        var request = new MockMultipartFile("request", "", "application/json",
                "{\"prompts\":{\"role\":\"\",\"instructions\":\"x\",\"outputContract\":\"x\"}}".getBytes());
        mvc.perform(multipart("/laboratory/documents/extract")
                .file(new MockMultipartFile("file", "plan.pdf", "application/pdf", new byte[]{1}))
                .file(request).header("X-AI-Service-Key", key))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returns422WithDiagnosticsForInvalidModelOutput() throws Exception {
        when(service.extract(any(), any())).thenReturn(new DocumentLabResponse(
                "qwen3:8b", 20, "not json", null, List.of("JSON invalido")));
        var request = new MockMultipartFile("request", "", "application/json", """
                {"sourceType":"STUDY_PLAN","prompts":{"role":"r","instructions":"i","outputContract":"o"}}
                """.getBytes());
        mvc.perform(multipart("/laboratory/documents/extract")
                .file(new MockMultipartFile("file", "plan.pdf", "application/pdf", new byte[]{1}))
                .file(request).header("X-AI-Service-Key", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.rawOutput").value("not json"))
                .andExpect(jsonPath("$.validationErrors[0]").value("JSON invalido"));
    }
}
