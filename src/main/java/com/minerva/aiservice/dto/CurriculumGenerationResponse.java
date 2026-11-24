package com.minerva.aiservice.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record CurriculumGenerationResponse(
        String grupoId,
        boolean persistido,
        JsonNode arbol,
        List<FuenteResultado> fuentesResultado,
        List<FuenteProcesada> fuentesProcesadas,
        List<String> advertencias,
        List<ErrorDetalle> errores) {

    public record FuenteResultado(
            String documentoId,
            String checksumSha256,
            String estado,
            int nodosAgregados,
            int contenidosAgregados,
            String mensaje) {
    }

    public record FuenteProcesada(
            String documentoId,
            String checksumSha256) {
    }

    public record ErrorDetalle(
            String codigo,
            String mensaje,
            boolean reintenable) {
    }
}
