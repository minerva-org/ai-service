package com.minerva.aiservice.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import tools.jackson.databind.JsonNode;

final class CurriculumSourceIndex {
    private static final int MAX_FRAGMENTS = 24;
    private static final int MAX_WORDS_PER_FRAGMENT = 140;
    /**
     * Short sources with an identifiable curriculum heading may be retained
     * even when their wording does not overlap the current tree.
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
        if (arbol == null || !arbol.isObject()) {
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
            int score = score(fragment.text(), keywords);
            if (score > 0 && isHeading(fragment.text())) {
                score += 3;
            }
            fragment.score = score;
        }

        List<UnitKeywords> units = collectUnitKeywords(arbol);
        if (units.isEmpty()) {
            boolean hasSubjectMatch = candidates.stream()
                    .anyMatch(fragment -> score(fragment.text(), keywords) > 0);
            boolean hasCurriculumHeading = candidates.stream()
                    .anyMatch(fragment -> isHeading(fragment.text()));
            if (!hasSubjectMatch && !hasCurriculumHeading) {
                return "";
            }
            return formatEvenlySelected(candidates);
        }

        boolean hasRelevantContent = candidates.stream()
                .anyMatch(fragment -> fragment.score > 0);
        if (!hasRelevantContent) {
            boolean shortText = text.length() <= SHORT_TEXT_THRESHOLD;
            boolean hasCurriculumHeading = candidates.stream()
                    .anyMatch(fragment -> isHeading(fragment.text()));
            LOGGER.log(Level.INFO,
                    "INDICE_FUENTE: sin coincidencias, textoCorto={0}, encabezadoCurricular={1}, caracteresTexto={2}, palabrasClave={3}",
                    new Object[]{shortText, hasCurriculumHeading, text.length(), keywords.size()});
            if (shortText && hasCurriculumHeading) {
                String limited = limitWords(text, MAX_WORDS_PER_FRAGMENT * 3);
                LOGGER.log(Level.INFO,
                        "INDICE_FUENTE: fallback texto corto, caracteres={0}",
                        limited.length());
                return limited;
            }
            return "";
        }

        LinkedHashMap<IndexedFragment, LinkedHashSet<String>> selected =
                new LinkedHashMap<>();
        for (UnitKeywords unit : units) {
            if (selected.size() >= MAX_FRAGMENTS) {
                break;
            }
            IndexedFragment best = candidates.stream()
                    .filter(fragment -> score(fragment.text(), unit.keywords()) > 0)
                    .max(Comparator
                            .comparingInt((IndexedFragment fragment) ->
                                    score(fragment.text(), unit.keywords()))
                            .thenComparingInt(fragment -> -fragment.position))
                    .orElse(null);
            if (best != null) {
                selected.computeIfAbsent(best, ignored -> new LinkedHashSet<>())
                        .add(unit.name());
            }
        }

        candidates.sort(Comparator
                .comparingInt((IndexedFragment fragment) -> fragment.score)
                .reversed()
                .thenComparingInt(fragment -> fragment.position));

        for (IndexedFragment fragment : candidates) {
            if (selected.size() >= MAX_FRAGMENTS) {
                break;
            }
            if (fragment.score > 0 && !fragment.text().isBlank()) {
                selected.putIfAbsent(fragment, new LinkedHashSet<>());
            }
        }

        if (selected.isEmpty()) {
            return "";
        }
        String relevantText = formatSelected(selected);
        LOGGER.log(Level.INFO,
                "INDICE_FUENTE: candidatos={0}, unidadesCubiertas={1}, seleccionados={2}, caracteresSeleccionados={3}, palabrasClave={4}",
                new Object[]{candidates.size(),
                        selected.values().stream().filter(value -> !value.isEmpty()).count(),
                        selected.size(),
                        relevantText.length(),
                        keywords.size()});
        return relevantText;
    }

    private static int score(String text, List<String> keywords) {
        String normalized = normalize(text);
        int score = 0;
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                score += Math.max(1, keyword.length() / 8);
            }
        }
        return score;
    }

    private static List<UnitKeywords> collectUnitKeywords(JsonNode tree) {
        List<UnitKeywords> units = new ArrayList<>();
        JsonNode children = tree.get("hijos");
        if (children == null || !children.isArray()) {
            return units;
        }
        for (JsonNode child : children) {
            if ("unidad".equalsIgnoreCase(text(child, "tipo"))) {
                List<String> keywords = collectKeywords(child);
                if (!keywords.isEmpty()) {
                    units.add(new UnitKeywords(text(child, "nombre"), keywords));
                }
            }
        }
        return units;
    }

    private static String formatEvenlySelected(List<IndexedFragment> candidates) {
        if (candidates.isEmpty()) {
            return "";
        }
        LinkedHashMap<IndexedFragment, LinkedHashSet<String>> selected =
                new LinkedHashMap<>();
        if (candidates.size() <= MAX_FRAGMENTS) {
            for (IndexedFragment candidate : candidates) {
                selected.put(candidate, new LinkedHashSet<>());
            }
        } else {
            for (int i = 0; i < MAX_FRAGMENTS; i++) {
                int index = i * (candidates.size() - 1) / (MAX_FRAGMENTS - 1);
                selected.put(candidates.get(index), new LinkedHashSet<>());
            }
        }
        return formatSelected(selected);
    }

    private static String formatSelected(
            LinkedHashMap<IndexedFragment, LinkedHashSet<String>> selected) {
        List<IndexedFragment> inSourceOrder = new ArrayList<>(selected.keySet());
        inSourceOrder.sort(Comparator.comparingInt(fragment -> fragment.position));
        List<String> fragments = new ArrayList<>(inSourceOrder.size());
        for (IndexedFragment fragment : inSourceOrder) {
            String unitContext = selected.get(fragment).isEmpty()
                    ? ""
                    : "Unidad curricular: "
                            + String.join(", ", selected.get(fragment))
                            + "\n";
            fragments.add(unitContext
                    + limitWords(fragment.text(), MAX_WORDS_PER_FRAGMENT));
        }
        return String.join("\n\n", fragments);
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
        return normalized.matches("^(unidad|tema|capitulo|chapter|seccion|section|escenario|bloque|modulo|parte|diapositiva|pagina|page|contenidos|objetivos)(\\b|\\d).*")
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

    private record UnitKeywords(String name, List<String> keywords) {
    }
}