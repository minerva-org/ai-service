package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.minerva.aiservice.dto.MetricsAssistantRequest;
import com.minerva.aiservice.dto.MetricsAssistantResponse;

import tools.jackson.databind.ObjectMapper;

class MetricsAssistantServiceTest {

    @Test
    void sendsOnlyProvidedStatisticsToConfiguredProvider() {
        AiModelClient client = mock(AiModelClient.class);
        ObjectMapper mapper = new ObjectMapper();
        MetricsAssistantService service =
                new MetricsAssistantService(client, mapper);
        var statistics = mapper.readTree(
                "{\"promedioGeneral\":74.5,\"incidenciasFoco\":3}");
        var request = new MetricsAssistantRequest(
                "GRUPO",
                "Grupo A",
                statistics,
                List.of(new MetricsAssistantRequest.Message(
                        "user", "Interpreta los datos.")));
        when(client.generateStructured(
                anyString(), anyString(), any()))
                .thenReturn("{\"answer\":\"Hay oportunidad de reforzamiento.\"}");
        when(client.model()).thenReturn("model-test");

        MetricsAssistantResponse response = service.consult(request);

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(client).generateStructured(
                anyString(), userPrompt.capture(), any());
        assertTrue(userPrompt.getValue().contains("\"promedioGeneral\":74.5"));
        assertTrue(userPrompt.getValue().contains("\"incidenciasFoco\":3"));
        assertEquals("Hay oportunidad de reforzamiento.", response.answer());
        assertEquals("model-test", response.model());
    }

    @Test
    void rejectsScopeOutsideMetricsContract() {
        AiModelClient client = mock(AiModelClient.class);
        ObjectMapper mapper = new ObjectMapper();
        MetricsAssistantService service =
                new MetricsAssistantService(client, mapper);
        var request = new MetricsAssistantRequest(
                "ALUMNO",
                "Alumno",
                mapper.createObjectNode(),
                List.of(new MetricsAssistantRequest.Message(
                        "user", "Consulta")));

        assertThrows(IllegalArgumentException.class,
                () -> service.consult(request));
    }
}
