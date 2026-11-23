package com.minerva.aiservice.dto;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CurriculumGenerationRequest(
        @NotBlank String grupoId,
        @NotNull JsonNode arbol,
        @NotNull List<@Valid FuenteNueva> fuentes,
        @NotNull List<@Valid FuenteProcesada> fuentesProcesadas,
        @NotBlank String modoFusion) {

    public CurriculumGenerationRequest {
        fuentes = fuentes == null ? new ArrayList<>() : List.copyOf(fuentes);
        fuentesProcesadas = fuentesProcesadas == null ? new ArrayList<>() : List.copyOf(fuentesProcesadas);
        modoFusion = modoFusion == null || modoFusion.isBlank() ? "conservadora" : modoFusion.trim();
    }

    public record FuenteNueva(
            @NotBlank String documentoId,
            @NotBlank String checksumSha256,
            @NotBlank String nombre) {
    }

    public record FuenteProcesada(
            @NotBlank String documentoId,
            @NotBlank String checksumSha256) {
    }
}
