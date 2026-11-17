package com.minerva.aiservice.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record DocumentLabResponse(
        String model,
        long durationMs,
        String rawOutput,
        JsonNode tree,
        List<String> validationErrors) {

    public boolean isValid() {
        return validationErrors.isEmpty();
    }
}
