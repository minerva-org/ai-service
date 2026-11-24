package com.minerva.aiservice.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger LOGGER =
            Logger.getLogger(QuizGenerationService.class.getName());

    private final AiModelClient aiModelClient;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    public QuizGenerationResponse generate(QuizGenerationRequest request) {
        String systemPrompt = """
                ## Rol:
                Eres un agente especializado en generar preguntas de evaluacion.

                ## Reglas:
                - Genera por defecto 10 preguntas si el prompt no indica una cantidad distinta.
                - Usa verdadero/falso y opcion multiple.
                - Cada pregunta debe tener exactamente una respuesta correcta.
                - Las opciones correctas deben basarse directamente en el contenido relacionado
                  de los conceptos recibidos.
                - Los distractores pueden generarse con libertad, pero deben ser cercanos,
                  plausibles y distinguirse claramente de la respuesta correcta.
                - Conserva el conceptId recibido.
                - No incluyas conceptos que no tengan contenido relacionado.
                - Devuelve exclusivamente JSON valido conforme al esquema.
                - Ejemplo JSON válido:
                  {"questions":[{"conceptId":"CONCEPTO-1","question":"¿Qué operación inserta un elemento al inicio?","options":[{"text":"Push","isCorrect":true},{"text":"Pop","isCorrect":false},{"text":"Peek","isCorrect":false},{"text":"Sort","isCorrect":false}]}]}
                - Ejemplo JSON inválido:
                  {"questions":[{"conceptId":"CONCEPTO-1","question":"¿Qué operación inserta?","options":[{"text":"Push","esCorrecta":true},{"text":"Pop","esCorrecta":false}]}]}
                - El segundo ejemplo es inválido porque usa "esCorrecta" en lugar de "isCorrect",
                  omite una opción con exactamente una respuesta correcta válida y no cumple el contrato.
                """;

        String raw = aiModelClient.generateStructured(
                systemPrompt, toJson(request), quizSchema());
        QuizGenerationResponse result;
        try {
            result = objectMapper.readValue(raw, QuizGenerationResponse.class);
        } catch (tools.jackson.core.JacksonException exception) {
            LOGGER.log(Level.WARNING,
                    "QUIZ_RESPUESTA_INVALIDA: respuesta={0}, detalle={1}",
                    new Object[]{raw, exception.getMessage()});
            throw new AiModelClient.InvalidModelResponseException(
                    "El proveedor devolvió JSON inválido para el quiz.",
                    exception);
        }
        ensureValid(result, request);
        LOGGER.log(Level.INFO,
                "QUIZ_GENERACION_OK: conceptos={0}, preguntas={1}",
                new Object[]{request.concepts().size(),
                        result.questions().size()});
        return result;
    }

    private Map<String, Object> quizSchema() {
        Map<String, Object> option = Map.of(
                "type", "object",
                "properties", Map.of(
                        "text", Map.of("type", "string", "minLength", 1),
                        "isCorrect", Map.of("type", "boolean")),
                "required", List.of("text", "isCorrect"),
                "additionalProperties", false);
        Map<String, Object> question = Map.of(
                "type", "object",
                "properties", Map.of(
                        "conceptId", Map.of("type", "string", "minLength", 1),
                        "question", Map.of("type", "string", "minLength", 1),
                        "options", Map.of(
                                "type", "array",
                                "items", option,
                                "minItems", 2)),
                "required", List.of("conceptId", "question", "options"),
                "additionalProperties", false);
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "questions", Map.of(
                                "type", "array",
                                "items", question,
                                "minItems", 1)),
                "required", List.of("questions"),
                "additionalProperties", false);
    }

    private void ensureValid(QuizGenerationResponse result, QuizGenerationRequest request) {
        if (!validator.validate(result).isEmpty()) {
            LOGGER.log(Level.WARNING,
                    "QUIZ_CONTRATO_INVALIDO: respuesta={0}",
                    objectMapper.valueToTree(result));
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
                LOGGER.log(Level.WARNING,
                        "QUIZ_RESPUESTA_INVALIDA: conceptId={0}, respuestasCorrectas={1}",
                        new Object[]{question.conceptId(), correctOptions});
                throw new AiModelClient.InvalidModelResponseException(
                        "Each generated question must have exactly one correct option.");
            }
            if (!selectedConceptIds.contains(question.conceptId())) {
                LOGGER.log(Level.WARNING,
                        "QUIZ_RESPUESTA_INVALIDA: conceptIdDesconocido={0}",
                        question.conceptId());
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
