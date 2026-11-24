package com.minerva.aiservice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

final class CurriculumGenerationSchema {

    private CurriculumGenerationSchema() {
    }

    static Map<String, Object> schema() {
        Map<String, Object> contentItem = Map.of(
                "type", "object",
                "properties", Map.of(
                        "conceptoId", Map.of("type", "string", "minLength", 1),
                        "descripcion", Map.of("type", "string", "minLength", 1)),
                "required", List.of("conceptoId", "descripcion"),
                "additionalProperties", false);

        Map<String, Object> newNodeContent = Map.of(
                "type", "object",
                "properties", Map.of(
                        "descripcion", Map.of("type", "string", "minLength", 1)),
                "required", List.of("descripcion"),
                "additionalProperties", false);

        Map<String, Object> newNode = Map.of(
                "type", "object",
                "properties", Map.of(
                        "tipo", Map.of("type", "string",
                                "enum", List.of("unidad", "tema", "concepto")),
                        "nombre", Map.of("type", "string", "minLength", 1),
                        "parentId", Map.of("type", "string", "minLength", 1),
                        "contenido", Map.of(
                                "type", "array",
                                "items", newNodeContent)),
                "required", List.of("tipo", "nombre", "parentId"),
                "additionalProperties", false);

        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "contenidos", Map.of(
                                "type", "array",
                                "items", contentItem),
                        "nodos", Map.of(
                                "type", "array",
                                "items", newNode)),
                "required", List.of("contenidos", "nodos"),
                "additionalProperties", false);
    }

    static List<String> validateTree(JsonNode tree) {
        List<String> errors = new ArrayList<>();
        validateTreeNode(tree, "$.arbol", errors);
        return List.copyOf(errors);
    }

    private static void validateTreeNode(
            JsonNode node,
            String path,
            List<String> errors) {
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
        if (hijos != null && !hijos.isArray()) {
            errors.add(path + ".hijos: se esperaba una lista.");
        } else if (hijos != null) {
            for (int i = 0; i < hijos.size(); i++) {
                validateTreeNode(hijos.get(i),
                        path + ".hijos[" + i + "]",
                        errors);
            }
        }
    }

    static List<String> validate(JsonNode operations) {
        List<String> errors = new ArrayList<>();
        if (operations == null || !operations.isObject()) {
            errors.add("$.operaciones: se esperaba un objeto.");
            return errors;
        }

        JsonNode contenidos = operations.get("contenidos");
        if (contenidos == null || !contenidos.isArray()) {
            errors.add("$.operaciones.contenidos: se esperaba una lista.");
        } else {
            for (int i = 0; i < contenidos.size(); i++) {
                JsonNode item = contenidos.get(i);
                String path = "$.operaciones.contenidos[" + i + "]";
                if (item == null || !item.isObject()) {
                    errors.add(path + ": se esperaba un objeto.");
                    continue;
                }
                requireText(item, "conceptoId", path, errors);
                requireText(item, "descripcion", path, errors);
            }
        }

        JsonNode nodos = operations.get("nodos");
        if (nodos == null || !nodos.isArray()) {
            errors.add("$.operaciones.nodos: se esperaba una lista.");
        } else {
            Set<String> types = Set.of("unidad", "tema", "concepto");
            for (int i = 0; i < nodos.size(); i++) {
                JsonNode item = nodos.get(i);
                String path = "$.operaciones.nodos[" + i + "]";
                if (item == null || !item.isObject()) {
                    errors.add(path + ": se esperaba un objeto.");
                    continue;
                }
                requireText(item, "tipo", path, errors);
                requireText(item, "nombre", path, errors);
                requireText(item, "parentId", path, errors);
                JsonNode type = item.get("tipo");
                if (type != null && type.isString()
                        && !types.contains(type.asString())) {
                    errors.add(path + ".tipo: tipo de nodo no permitido.");
                }
                JsonNode contenido = item.get("contenido");
                if (contenido != null && !contenido.isArray()) {
                    errors.add(path + ".contenido: se esperaba una lista.");
                } else if (contenido != null) {
                    for (int j = 0; j < contenido.size(); j++) {
                        JsonNode content = contenido.get(j);
                        if (content == null || !content.isObject()) {
                            errors.add(path + ".contenido[" + j + "]: se esperaba un objeto.");
                            continue;
                        }
                        requireText(content, "descripcion",
                                path + ".contenido[" + j + "]", errors);
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void requireText(
            JsonNode object,
            String field,
            String path,
            List<String> errors) {
        JsonNode value = object.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            errors.add(path + "." + field + ": se requiere texto no vacio.");
        }
    }
}
