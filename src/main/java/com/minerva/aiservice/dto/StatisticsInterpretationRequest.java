package com.minerva.aiservice.dto;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StatisticsInterpretationRequest(
        @NotBlank @Size(max = 10_000) String prompt,
        @NotNull JsonNode screenContext) {
}
