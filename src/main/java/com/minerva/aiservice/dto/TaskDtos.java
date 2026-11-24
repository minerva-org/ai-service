package com.minerva.aiservice.dto;

import tools.jackson.databind.JsonNode;

public final class TaskDtos {
    private TaskDtos() {
    }

    public record CreateRequest(
            String tipo,
            JsonNode datos) {
    }

    public record CreateResponse(
            String tareaId) {
    }

    public record StatusResponse(
            String tareaId,
            String tipo,
            String estado,
            String progreso,
            JsonNode resultado,
            String error) {
    }
}
