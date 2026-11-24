package com.minerva.aiservice.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import tools.jackson.databind.JsonNode;

final class CurriculumSourceIndex {
    private static final int MAX_FRAGMENTS = 8;
    private static final int MAX_WORDS_PER_FRAGMENT = 220;
    /**
     * Fix 4: umbral de "texto corto". Un texto por debajo de este tamano
     * no se descarta aunque no coincida con keywords del arbol: se envia
     * completo al modelo, que decidira si aporta contenido curricular.
     * Evita que fuentes breves (un parrafo sin encabezado reconocible)
     * se marquen como "sin texto curricular relevante" sin intentarlo.
     */
    private static final int SHORT_TEXT_THRESHOLD = 4_000;
    private static final Logger LOGGER =
            Logger.getLogger(CurriculumSourceIndex.class.getName());

    private CurriculumSourceIndex() {
    }

    static String selectRelevantFragments(
            String text,
            JsonNode arbol) {
        if (text == null || text.isBlank()) {
            return "";
        }

        List<String> keywords = collectKeywords(arbol);
        List<IndexedFragment> candidates = index(text);
        if (candidates.isEmpty()) {
            LOGGER.log(Level.WARNING,
                    "INDICE_FUENTE: candidatos=0, caracteresTexto={0}",
                    text.length());
            return "";
        }

        for (IndexedFragment fragment : candidates) {
            String normalized = normalize(fragment.text());
            int score = 0;
            for (String keyword : keywords) {
                if (normalized.contains(keyword)) {
                    score += Math.max(1, keyword.length() / 8);
                }
            }
            if (isHeading(fragment.text())) {
                score += 3;
            }
            fragment.score = score;
        }

        boolean hasRelevantContent = candidates.stream()
                .anyMatch(fragment -> fragment.score > 0);
        if (!hasRelevantContent) {
            // Fix 4: si el texto es corto, no lo descartamos. Lo enviamos
            // completo (acotado por palabras) al modelo. Si es largo y no
            // hay coincidencias, sigue descartandose para no quemar contexto.
            boolean shortText = text.length() <= SHORT_TEXT_THRESHOLD;
            LOGGER.log(Level.INFO,
                    "INDICE_FUENTE: sin coincidencias, textoCorto={0}, caracteresTexto={1}, palabrasClave={2}",
                    new Object[]{shortText, text.length(), keywords.size()});
            if (shortText) {
                String limited = limitWords(text, MAX_WORDS_PER_FRAGMENT * 3);
                LOGGER.log(Level.INFO,
                        "INDICE_FUENTE: fallback texto corto, caracteres={0}",
                        limited.length());
                return limited;
            }
            LOGGER.log(Level.WARNING,
                    "INDICE_FUENTE: candidatos={0}, relevantes=0, textoDescartadoPorLargo, caracteresTexto={1}",
                    new Object[]{candidates.size(), text.length()});
            return "";
        }

        candidates.sort(Comparator
                .comparingInt((IndexedFragment fragment) -> fragment.score)
                .reversed()
                .thenComparingInt(fragment -> fragment.position));

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (IndexedFragment fragment : candidates) {
            if (selected.size() >= MAX_FRAGMENTS) {
                break;
            }
            if (!fragment.text().isBlank()) {
                selected.add(limitWords(fragment.text(), MAX_WORDS_PER_FRAGMENT));
            }
        }

        if (selected.isEmpty()) {
            return "";
        }
        String relevantText = String.join("\n\n", selected);
        LOGGER.log(Level.INFO,
                "INDICE_FUENTE: candidatos={0}, seleccionados={1}, caracteresSeleccionados={2}, palabrasClave={3}",
                new Object[]{candidates.size(), selected.size(), relevantText.length(), keywords.size()});
        return relevantText;
    }

    private static List<IndexedFragment> index(String text) {
        List<IndexedFragment> result = new ArrayList<>();
        String[] lines = text.split("\\R");
        StringBuilder paragraph = new StringBuilder();
        int position = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                addParagraph(result, paragraph, position++);
                continue;
            }
            if (isHeading(trimmed)) {
                addParagraph(result, paragraph, position++);
                result.add(new IndexedFragment(trimmed, position++, 0));
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(trimmed);
            }
        }
        addParagraph(result, paragraph, position);
        return result;
    }

    private static void addParagraph(
            List<IndexedFragment> result,
            StringBuilder paragraph,
            int position) {
        if (paragraph.isEmpty()) {
            return;
        }
        result.add(new IndexedFragment(
                paragraph.toString().trim(),
                position,
                0));
        paragraph.setLength(0);
    }

    private static boolean isHeading(String text) {
        String normalized = normalize(text);
        if (normalized.length() > 140) {
            return false;
        }
        return normalized.matches("^(unidad|tema|capitulo|chapter|seccion|section)(\\b|\\d).*")
                || normalized.matches("^\\d+[\\.)].*")
                || (text.length() < 90
                        && text.endsWith(":")
                        && Character.isUpperCase(text.charAt(0)));
    }

    private static List<String> collectKeywords(JsonNode arbol) {
        Set<String> keywords = new LinkedHashSet<>();
        collectKeywords(arbol, keywords);
        return new ArrayList<>(keywords);
    }

    private static void collectKeywords(
            JsonNode node,
            Set<String> keywords) {
        if (node == null || !node.isObject()) {
            return;
        }
        String name = text(node, "nombre");
        if (!name.isBlank()) {
            String normalized = normalize(name);
            if (normalized.length() >= 3) {
                keywords.add(normalized);
            }
            for (String word : normalized.split("\\s+")) {
                if (word.length() >= 5) {
                    keywords.add(word);
                }
            }
        }
        JsonNode children = node.get("hijos");
        if (children != null && children.isArray()) {
            for (JsonNode child : children) {
                collectKeywords(child, keywords);
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString()
                ? value.asString().trim()
                : "";
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String limitWords(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) {
            return text;
        }
        return String.join(" ", Arrays.copyOfRange(words, 0, maxWords)) + " ...";
    }

    private static final class IndexedFragment {
        private final String text;
        private final int position;
        private int score;

        private IndexedFragment(String text, int position, int score) {
            this.text = text;
            this.position = position;
            this.score = score;
        }

        private String text() {
            return text;
        }
    }
}