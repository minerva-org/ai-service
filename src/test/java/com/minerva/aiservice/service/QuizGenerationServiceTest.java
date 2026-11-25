package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minerva.aiservice.dto.QuizGenerationRequest;
import com.minerva.aiservice.dto.QuizGenerationResponse;

import tools.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class QuizGenerationServiceTest {

    @Test
    void rejectsQuestionWithMoreThanOneCorrectOption() {
        OllamaClient ollamaClient = mock(OllamaClient.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        QuizGenerationService service = new QuizGenerationService(
                ollamaClient,
                new ObjectMapper(),
                validator);

        QuizGenerationRequest request = new QuizGenerationRequest(
                "Genera una pregunta",
                List.of(new QuizGenerationRequest.ConceptInput(
                        "concept-1",
                        "Concepto",
                        List.of(new QuizGenerationRequest.ContentInput(
                                "Contenido verificable.")))));
        QuizGenerationResponse response = new QuizGenerationResponse(List.of(
                new QuizGenerationResponse.QuestionDTO(
                        "concept-1",
                        "Concepto",
                        "Pregunta",
                        "Contenido verificable.",
                        List.of(
                                new QuizGenerationResponse.OptionDTO("Correcta A", true),
                                new QuizGenerationResponse.OptionDTO("Correcta B", true)))));

        when(ollamaClient.generateStructured(anyString(), anyString(), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new ObjectMapper().writeValueAsString(response));

        assertThrows(
                AiModelClient.InvalidModelResponseException.class,
                () -> service.generate(request));
    }

    @Test
    void rejectsEvidenceFromAnotherConcept() throws Exception {
        AiModelClient client = mock(AiModelClient.class);
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        QuizGenerationService service = new QuizGenerationService(
                client,
                new ObjectMapper(),
                validator);
        QuizGenerationRequest request = new QuizGenerationRequest(
                "Genera una pregunta",
                List.of(
                        new QuizGenerationRequest.ConceptInput(
                                "concept-1",
                                "Concepto uno",
                                List.of(new QuizGenerationRequest.ContentInput(
                                        "Evidencia del primer concepto."))),
                        new QuizGenerationRequest.ConceptInput(
                                "concept-2",
                                "Concepto dos",
                                List.of(new QuizGenerationRequest.ContentInput(
                                        "Evidencia del segundo concepto.")))));
        QuizGenerationResponse response = new QuizGenerationResponse(List.of(
                new QuizGenerationResponse.QuestionDTO(
                        "concept-1",
                        "Concepto uno",
                        "Pregunta relacionada",
                        "Evidencia del segundo concepto.",
                        List.of(
                                new QuizGenerationResponse.OptionDTO("Correcta", true),
                                new QuizGenerationResponse.OptionDTO("Distractor", false)))));

        when(client.generateStructured(
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new ObjectMapper().writeValueAsString(response));

        assertThrows(
                AiModelClient.InvalidModelResponseException.class,
                () -> service.generate(request));
    }

    @Test
    void keepsValidQuestionsWhenAnotherQuestionIsInvalid() throws Exception {
        AiModelClient client = mock(AiModelClient.class);
        ObjectMapper mapper = new ObjectMapper();
        QuizGenerationService service = new QuizGenerationService(
                client,
                mapper,
                Validation.buildDefaultValidatorFactory().getValidator());
        QuizGenerationRequest request = new QuizGenerationRequest(
                "Genera preguntas",
                List.of(new QuizGenerationRequest.ConceptInput(
                        "concept-1",
                        "Concepto",
                        List.of(new QuizGenerationRequest.ContentInput(
                                "Contenido verificable.")))));
        QuizGenerationResponse response = new QuizGenerationResponse(List.of(
                new QuizGenerationResponse.QuestionDTO(
                        "concept-1",
                        "Concepto",
                        "Pregunta válida",
                        "Contenido verificable.",
                        List.of(
                                new QuizGenerationResponse.OptionDTO("Correcta", true),
                                new QuizGenerationResponse.OptionDTO("Distractor", false))),
                new QuizGenerationResponse.QuestionDTO(
                        "concept-1",
                        "Concepto",
                        "Pregunta inválida",
                        "Contenido verificable.",
                        List.of(
                                new QuizGenerationResponse.OptionDTO("Correcta A", true),
                                new QuizGenerationResponse.OptionDTO("Correcta B", true)))));

        when(client.generateStructured(
                anyString(), anyString(),
                org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(mapper.writeValueAsString(response));

        QuizGenerationResponse result = service.generate(request);

        assertEquals(1, result.questions().size());
        assertEquals("Pregunta válida", result.questions().get(0).question());
    }
}
