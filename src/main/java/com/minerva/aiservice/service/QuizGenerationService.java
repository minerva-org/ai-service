package com.minerva.aiservice.service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
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
                - Cada pregunta debe evaluarse ÚNICAMENTE con el contenido del conceptId que declares.
                - El enunciado, la respuesta correcta y todos los distractores deben poder sustentarse
                  con el contenido de ese concepto; no uses conocimiento externo ni datos de otros conceptos.
                - Copia en "evidence" un fragmento literal y suficiente del contenido recibido para demostrar
                  la relación. No parafrasees "evidence".
                - Cada pregunta debe tener exactamente una respuesta correcta.
                - Las opciones correctas deben basarse directamente en el contenido relacionado
                  de los conceptos recibidos.
                - Los distractores pueden generarse con libertad, pero deben ser cercanos,
                  plausibles y distinguirse claramente de la respuesta correcta.
                - Conserva el conceptId recibido.
                - Conserva también el nombre exacto recibido en "conceptName". No lo inventes ni lo parafrasees.
                - No incluyas conceptos que no tengan contenido relacionado.
                - Si el contenido recibido no permite crear una pregunta inequívoca, omite ese concepto.
                - Devuelve exclusivamente JSON valido conforme al esquema.
                - Ejemplo JSON válido:
                  {"questions":[{"conceptId":"CONCEPTO-1","conceptName":"Pilas","question":"¿Qué operación inserta un elemento al inicio?","evidence":"Push inserta un elemento al inicio.","options":[{"text":"Push","isCorrect":true},{"text":"Pop","isCorrect":false},{"text":"Peek","isCorrect":false},{"text":"Sort","isCorrect":false}]}]}
                - Ejemplo JSON inválido:
                  {"questions":[{"conceptId":"CONCEPTO-1","question":"¿Qué operación inserta?","options":[{"text":"Push","esCorrecta":true},{"text":"Pop","esCorrecta":false}]}]}
                - El segundo ejemplo es inválido porque usa "esCorrecta" en lugar de "isCorrect",
                  omite una opción con exactamente una respuesta correcta válida y no cumple el contrato.
                """;

        String raw = aiModelClient.generateStructured(
                systemPrompt, toJson(request), quizSchema());
        QuizGenerationResponse result;
        try {
            tools.jackson.databind.JsonNode rawResult =
                    objectMapper.readTree(raw);
            tools.jackson.databind.JsonNode questions =
                    rawResult.get("questions");
            if (questions != null && questions.isArray()) {
                Map<String, String> conceptNames =
                        request.concepts().stream()
                                .collect(Collectors.toMap(
                                        QuizGenerationRequest.ConceptInput::id,
                                        QuizGenerationRequest.ConceptInput::name));
                for (tools.jackson.databind.JsonNode question : questions) {
                    if (question instanceof tools.jackson.databind.node.ObjectNode object
                            && (!object.has("conceptName")
                                || object.path("conceptName").asString().isBlank())) {
                        String conceptName =
                                conceptNames.get(object.path("conceptId").asString());
                        if (conceptName != null) {
                            object.put("conceptName", conceptName);
                        }
                    }
                }
            }
            result = objectMapper.treeToValue(
                    rawResult, QuizGenerationResponse.class);
        } catch (tools.jackson.core.JacksonException exception) {
            LOGGER.log(Level.WARNING,
                    "QUIZ_RESPUESTA_INVALIDA: respuesta={0}, detalle={1}",
                    new Object[]{raw, exception.getMessage()});
            throw new AiModelClient.InvalidModelResponseException(
                    "El proveedor devolvió JSON inválido para el quiz.",
                    exception);
        }
        result = retainValidQuestions(result, request);
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
                        "conceptName", Map.of("type", "string", "minLength", 1),
                        "question", Map.of("type", "string", "minLength", 1),
                        "evidence", Map.of("type", "string", "minLength", 1),
                        "options", Map.of(
                                "type", "array",
                                "items", option,
                                "minItems", 2)),
                "required", List.of(
                        "conceptId", "conceptName",
                        "question", "evidence", "options"),
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

    private QuizGenerationResponse retainValidQuestions(
            QuizGenerationResponse result,
            QuizGenerationRequest request) {
        Map<String, QuizGenerationRequest.ConceptInput> conceptsById =
                request.concepts().stream()
                .collect(Collectors.toMap(
                        QuizGenerationRequest.ConceptInput::id,
                        concept -> concept));

        List<QuizGenerationResponse.QuestionDTO> validQuestions =
                new ArrayList<>();
        for (QuizGenerationResponse.QuestionDTO question : result.questions()) {
            if (question == null
                    || !validator.validate(question).isEmpty()
                    || question.question() == null
                    || question.question().isBlank()) {
                LOGGER.log(Level.WARNING,
                        "QUIZ_PREGUNTA_DESCARTADA: motivo=contrato");
                continue;
            }
            long correctOptions = question.options().stream()
                    .filter(QuizGenerationResponse.OptionDTO::isCorrect)
                    .count();
            if (correctOptions != 1) {
                LOGGER.log(Level.WARNING,
                        "QUIZ_RESPUESTA_INVALIDA: conceptId={0}, respuestasCorrectas={1}",
                        new Object[]{question.conceptId(), correctOptions});
                continue;
            }
            QuizGenerationRequest.ConceptInput concept =
                    conceptsById.get(question.conceptId());
            if (concept == null) {
                LOGGER.log(Level.WARNING,
                        "QUIZ_RESPUESTA_INVALIDA: conceptIdDesconocido={0}",
                        question.conceptId());
                continue;
            }
            if (!concept.name().equalsIgnoreCase(question.conceptName())) {
                LOGGER.log(Level.WARNING,
                        "QUIZ_RESPUESTA_INVALIDA: conceptId={0}, conceptoEsperado={1}, conceptoRecibido={2}",
                        new Object[]{question.conceptId(),
                                concept.name(), question.conceptName()});
                continue;
            }
            List<String> contents = concept.content().stream()
                    .map(QuizGenerationRequest.ContentInput::description)
                    .toList();
            String normalizedEvidence = normalize(question.evidence());
            if (contents.stream().map(QuizGenerationService::normalize)
                    .noneMatch(content -> content.contains(normalizedEvidence)
                            || normalizedEvidence.contains(content))) {
                LOGGER.log(Level.WARNING,
                        "QUIZ_RESPUESTA_INVALIDA: conceptId={0}, evidenciaNoEncontrada=true",
                        question.conceptId());
                continue;
            }
            validQuestions.add(question);
        }
        if (validQuestions.isEmpty()) {
            throw new AiModelClient.InvalidModelResponseException(
                    "No valid quiz questions remained after validation.");
        }
        LOGGER.log(Level.INFO,
                "QUIZ_PREGUNTAS_VALIDAS: recibidas={0}, conservadas={1}, descartadas={2}",
                new Object[]{result.questions().size(),
                        validQuestions.size(),
                        result.questions().size() - validQuestions.size()});
        return new QuizGenerationResponse(validQuestions);
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim().toLowerCase(
                java.util.Locale.ROOT);
    }

    private String toJson(QuizGenerationRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not serialize quiz request.", exception);
        }
    }
}
