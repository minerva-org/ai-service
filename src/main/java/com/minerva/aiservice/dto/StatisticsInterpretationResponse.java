package com.minerva.aiservice.dto;

import jakarta.validation.constraints.NotBlank;

public record StatisticsInterpretationResponse(
        @NotBlank String answer,
        boolean insufficientData) {
}
