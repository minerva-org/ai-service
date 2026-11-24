package com.minerva.aiservice.service;

import org.springframework.stereotype.Service;

import com.minerva.aiservice.dto.StatisticsInterpretationRequest;
import com.minerva.aiservice.dto.StatisticsInterpretationResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class StatisticsInterpretationService {

    private final AiModelClient aiModelClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public StatisticsInterpretationResponse interpret(StatisticsInterpretationRequest request) {
        String systemPrompt = """
                Interpreta el análisis ya calculado y autorizado por Minerva para responder la pregunta.
                Usa únicamente los valores proporcionados; no inventes cifras, no recalcules métricas
                ausentes y no intentes ampliar el ámbito de los datos. Si el contexto no basta para responder,
                indícalo en answer y establece insufficientData en true. Devuelve únicamente JSON válido.
                """;

        StatisticsInterpretationResponse result = aiModelClient.generateJson(
                systemPrompt,
                toJson(request),
                StatisticsInterpretationResponse.class);
        if (!validator.validate(result).isEmpty()) {
            throw new AiModelClient.InvalidModelResponseException(
                    "Model output did not match the statistics response schema.");
        }
        return result;
    }

    private String toJson(StatisticsInterpretationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize statistics request.", exception);
        }
    }
}
