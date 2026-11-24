package com.minerva.aiservice.service;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.minerva.aiservice.dto.QuizGenerationRequest;
import com.minerva.aiservice.dto.QuizGenerationResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class QuizGenerationService {

    private final AiModelClient aiModelClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public QuizGenerationResponse generate(QuizGenerationRequest request) {
        String systemPrompt = """
                Genera preguntas de quiz en el idioma solicitado por el usuario, basadas únicamente en el prompt
                y los conceptos recibidos. Cada pregunta debe tener exactamente una opción correcta y al menos
                un distractor. Conserva el conceptId de un concepto recibido. Devuelve únicamente JSON válido.
                """;

        QuizGenerationResponse result = aiModelClient.generateJson(
                systemPrompt,
                toJson(request),
                QuizGenerationResponse.class);
        ensureValid(result, request);
        return result;
    }

    private void ensureValid(QuizGenerationResponse result, QuizGenerationRequest request) {
        if (!validator.validate(result).isEmpty()) {
            throw new AiModelClient.InvalidModelResponseException(
                    "Model output did not match the quiz schema.");
        }

        Set<String> selectedConceptIds = request.concepts().stream()
                .map(QuizGenerationRequest.ConceptInput::id)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());

        for (QuizGenerationResponse.QuestionDTO question : result.questions()) {
            long correctOptions = question.options().stream()
                    .filter(QuizGenerationResponse.OptionDTO::isCorrect)
                    .count();
            if (correctOptions != 1) {
                throw new AiModelClient.InvalidModelResponseException(
                        "Each generated question must have exactly one correct option.");
            }
            if (!selectedConceptIds.contains(question.conceptId())) {
                throw new AiModelClient.InvalidModelResponseException(
                        "Generated question references a concept that was not selected.");
            }
        }
    }

    private String toJson(QuizGenerationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize quiz request.", exception);
        }
    }
}
