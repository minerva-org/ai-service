package com.minerva.aiservice.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record QuizGenerationRequest(
        @NotBlank @Size(max = 10_000) String prompt,
        @NotEmpty @Size(max = 100) List<@Valid ConceptInput> concepts) {

    public record ConceptInput(
            @NotBlank String id,
            @NotBlank String name,
            @Valid List<ContentInput> content) {
    }

    public record ContentInput(@NotBlank String description) {
    }
}
