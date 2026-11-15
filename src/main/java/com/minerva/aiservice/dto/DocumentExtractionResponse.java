package com.minerva.aiservice.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record DocumentExtractionResponse(@NotEmpty List<@Valid UnitDTO> units) {

    public record UnitDTO(@NotBlank String name, @NotEmpty List<@Valid TopicDTO> topics) {
    }

    public record TopicDTO(@NotBlank String name, @NotEmpty List<@Valid ConceptDTO> concepts) {
    }

    public record ConceptDTO(@NotBlank String name) {
    }
}
