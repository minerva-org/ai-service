package com.minerva.aiservice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;

final class DocumentExtractionSchema {

    private static final String[] CHILDREN = {"units", "topics", "concepts"};

    private DocumentExtractionSchema() {
    }

    static Map<String, Object> schema() {
        return nodeSchema(0);
    }

    private static Map<String, Object> nodeSchema(int level) {
        Map<String, Object> name = Map.of("type", "string", "minLength", 1);
        if (level == 3) {
            return Map.of("type", "object", "properties", Map.of("name", name),
                    "required", List.of("name"), "additionalProperties", false);
        }
        String child = CHILDREN[level];
        Map<String, Object> children = Map.of(
                "type", "array", "items", nodeSchema(level + 1), "minItems", level == 0 ? 0 : 1);
        return Map.of("type", "object",
                "properties", level == 0 ? Map.of(child, children) : Map.of("name", name, child, children),
                "required", level == 0 ? List.of(child) : List.of("name", child),
                "additionalProperties", false);
    }

    static List<String> validate(JsonNode tree) {
        List<String> errors = new ArrayList<>();
        validateNode(tree, "$", 0, errors);
        return List.copyOf(errors);
    }

    private static void validateNode(JsonNode node, String path, int level, List<String> errors) {
        if (node == null || !node.isObject()) {
            errors.add(path + ": se esperaba un objeto.");
            return;
        }
        Set<String> allowed = level == 0 ? Set.of("units")
                : level == 3 ? Set.of("name") : Set.of("name", CHILDREN[level]);
        for (var field : node.properties()) {
            if (!allowed.contains(field.getKey())) {
                errors.add(path + "." + field.getKey() + ": campo no permitido.");
            }
        }
        if (level > 0) {
            JsonNode name = node.get("name");
            if (name == null || !name.isString() || name.asString().isBlank()) {
                errors.add(path + ".name: se requiere texto no vacio.");
            }
        }
        if (level == 3) {
            return;
        }
        String child = CHILDREN[level];
        JsonNode children = node.get(child);
        if (children == null || !children.isArray()) {
            errors.add(path + "." + child + ": se esperaba una lista.");
            return;
        }
        if (level > 0 && children.isEmpty()) {
            errors.add(path + "." + child + ": la lista no puede estar vacia.");
        }
        for (int i = 0; i < children.size(); i++) {
            validateNode(children.get(i), path + "." + child + "[" + i + "]", level + 1, errors);
        }
    }
}
