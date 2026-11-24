package com.minerva.aiservice.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record MetricsAssistantRequest(
        @NotBlank String scope,
        String entityName,
        @NotNull JsonNode statistics,
        @NotNull @Size(min = 1, max = 12) List<@Valid Message> messages) {

    public record Message(
            @NotBlank String role,
            @NotBlank @Size(max = 8_000) String content) {
    }
}
