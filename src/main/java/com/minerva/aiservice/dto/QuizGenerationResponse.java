package com.minerva.aiservice.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record QuizGenerationResponse(@NotEmpty List<@Valid QuestionDTO> questions) {

    public record QuestionDTO(
            @NotBlank String conceptId,
            @NotBlank String question,
            @NotEmpty List<@Valid OptionDTO> options) {
    }

    public record OptionDTO(@NotBlank String text, boolean isCorrect) {
    }
}
