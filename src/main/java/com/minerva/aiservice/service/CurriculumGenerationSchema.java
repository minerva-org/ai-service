package com.minerva.aiservice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

final class CurriculumGenerationSchema {

    private CurriculumGenerationSchema() {
    }

    static Map<String, Object> schema() {
        Map<String, Object> content = Map.of(
                "type", "object",
                "properties", Map.of(
                        "descripcion", Map.of("type", "string", "minLength", 1)),
                "required", List.of("descripcion"),
                "additionalProperties", false);

        Map<String, Object> node = Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string", "minLength", 1),
                        "tipo", Map.of("type", "string", "enum", List.of("materia", "unidad", "tema", "concepto")),
                        "nombre", Map.of("type", "string", "minLength", 1),
                        "origen", Map.of("type", "string", "enum", List.of("materia", "usuario", "ia")),
                        "revisionEstado", Map.of("type", "string",
                                "enum", List.of("original", "nuevo", "modificado", "pendiente_revision")),
                        "estadoConcepto", Map.of("type", "string",
                                "enum", List.of("visto", "planeado", "con_contenido", "sin_contenido")),
                        "contenido", Map.of(
                                "type", "array",
                                "items", content),
                        "hijos", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "tipo", Map.of("type", "string"),
                                                "nombre", Map.of("type", "string"),
                                                "origen", Map.of("type", "string"),
                                                "revisionEstado", Map.of("type", "string"),
                                                "estadoConcepto", Map.of("type", "string"),
                                                "contenido", Map.of("type", "array"),
                                                "hijos", Map.of("type", "array")),
                                        "required", List.of("tipo", "nombre"),
                                        "additionalProperties", true))),
                "required", List.of("tipo", "nombre"),
                "additionalProperties", false);

        return node;
    }

    static List<String> validate(JsonNode tree) {
        List<String> errors = new ArrayList<>();
        if (tree == null || !tree.isObject()) {
            errors.add("$.arbol: se esperaba un objeto.");
            return errors;
        }
        validateNode(tree, "$.arbol", errors);
        return List.copyOf(errors);
    }

    private static void validateNode(JsonNode node, String path, List<String> errors) {
        if (node == null || !node.isObject()) {
            errors.add(path + ": se esperaba un objeto.");
            return;
        }
        JsonNode tipo = node.get("tipo");
        if (tipo == null || !tipo.isString() || tipo.asString().isBlank()) {
            errors.add(path + ".tipo: se requiere texto no vacio.");
        }
        JsonNode nombre = node.get("nombre");
        if (nombre == null || !nombre.isString() || nombre.asString().isBlank()) {
            errors.add(path + ".nombre: se requiere texto no vacio.");
        }
        JsonNode hijos = node.get("hijos");
        if (hijos != null && hijos.isArray()) {
            for (int i = 0; i < hijos.size(); i++) {
                validateNode(hijos.get(i), path + ".hijos[" + i + "]", errors);
            }
        }
        JsonNode contenido = node.get("contenido");
        if (contenido != null) {
            if (!contenido.isArray()) {
                errors.add(path + ".contenido: se esperaba una lista.");
            } else {
                for (int i = 0; i < contenido.size(); i++) {
                    JsonNode item = contenido.get(i);
                    if (item == null || !item.isObject()) {
                        errors.add(path + ".contenido[" + i + "]: se esperaba un objeto.");
                        continue;
                    }
                    JsonNode descripcion = item.get("descripcion");
                    if (descripcion == null || !descripcion.isString()
                            || descripcion.asString().isBlank()) {
                        errors.add(path + ".contenido[" + i + "].descripcion: se requiere texto no vacio.");
                    }
                }
            }
        }
    }
}
