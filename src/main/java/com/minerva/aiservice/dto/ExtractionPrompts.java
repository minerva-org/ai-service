package com.minerva.aiservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExtractionPrompts(
        @NotBlank @Size(max = 12000) String role,
        @NotBlank @Size(max = 12000) String instructions,
        @NotBlank @Size(max = 12000) String outputContract) {
}
