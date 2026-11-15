package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
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
                List.of(new QuizGenerationRequest.ConceptInput("concept-1", "Concepto")));
        QuizGenerationResponse response = new QuizGenerationResponse(List.of(
                new QuizGenerationResponse.QuestionDTO(
                        "concept-1",
                        "Pregunta",
                        List.of(
                                new QuizGenerationResponse.OptionDTO("Correcta A", true),
                                new QuizGenerationResponse.OptionDTO("Correcta B", true)))));

        when(ollamaClient.generateJson(anyString(), anyString(), eq(QuizGenerationResponse.class)))
                .thenReturn(response);

        assertThrows(
                OllamaClient.InvalidModelResponseException.class,
                () -> service.generate(request));
    }
}
