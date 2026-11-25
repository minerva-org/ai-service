package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class CurriculumSourceIndexTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void returnsEmptyForNullOrBlankText() throws Exception {
        var tree = objectMapper.readTree("""
                {"tipo":"materia","nombre":"Materia","hijos":[]}
                """);

        assertTrue(CurriculumSourceIndex
                .selectRelevantFragments(null, tree).isBlank());
        assertTrue(CurriculumSourceIndex
                .selectRelevantFragments("   ", tree).isBlank());
    }

    @Test
    void returnsEmptyForNullTree() {
        assertTrue(CurriculumSourceIndex
                .selectRelevantFragments("Texto relacionado", null).isBlank());
    }

    @Test
    void selectsFragmentsRelatedToTheCurriculumTree() throws Exception {
        var tree = objectMapper.readTree("""
                {
                  "tipo": "materia",
                  "nombre": "Estructura de Datos",
                  "hijos": [
                    {
                      "tipo": "unidad",
                      "nombre": "Estructuras lineales",
                      "hijos": [
                        {
                          "tipo": "tema",
                          "nombre": "Pilas",
                          "hijos": [
                            {
                              "tipo": "concepto",
                              "nombre": "Push",
                              "hijos": []
                            }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        String indexed = CurriculumSourceIndex.selectRelevantFragments("""
                Unidad 3: Estructuras lineales

                Una pila permite insertar elementos con Push y retirar el ultimo elemento.

                Las ecuaciones diferenciales son otro tema ajeno.
                """, tree);

        assertTrue(indexed.toLowerCase().contains("pila"));
        assertTrue(indexed.contains("Push"));
    }

    @Test
    void rejectsTextWithoutCurriculumMatches() throws Exception {
        var tree = objectMapper.readTree("""
                {
                  "tipo": "materia",
                  "nombre": "Estructura de Datos",
                  "hijos": []
                }
                """);

        String indexed = CurriculumSourceIndex.selectRelevantFragments(
                "Receta de cocina con ingredientes y pasos.",
                tree);

        assertTrue(indexed.isBlank());
    }

    @Test
    void keepsCourseHeadingAsAnIndexedFragment() throws Exception {
        var tree = objectMapper.readTree("""
                {
                  "tipo": "materia",
                  "nombre": "Programacion",
                  "hijos": [
                    {
                      "tipo": "unidad",
                      "nombre": "Recursividad",
                      "hijos": []
                    }
                  ]
                }
                """);

        String indexed = CurriculumSourceIndex.selectRelevantFragments("""
                Unidad 2: Recursividad

                Una funcion recursiva se invoca a si misma.
                """, tree);

        assertFalse(indexed.isBlank());
        assertTrue(indexed.contains("Recursividad"));
    }

    @Test
    void keepsRelevantFragmentsFromEveryExistingUnit() throws Exception {
        var tree = objectMapper.readTree("""
                {
                  "tipo": "materia",
                  "nombre": "Programacion Orientada a Objetos",
                  "hijos": [
                    {
                      "tipo": "unidad",
                      "nombre": "Fundamentos",
                      "hijos": [
                        {
                          "tipo": "tema",
                          "nombre": "Clases y objetos",
                          "hijos": [
                            {"tipo": "concepto", "nombre": "Atributos"}
                          ]
                        }
                      ]
                    },
                    {
                      "tipo": "unidad",
                      "nombre": "Herencia",
                      "hijos": [
                        {
                          "tipo": "tema",
                          "nombre": "Jerarquia",
                          "hijos": [
                            {"tipo": "concepto", "nombre": "Sobrescritura"}
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);
        String text = """
                Unidad 1: Fundamentos

                Las clases definen objetos y sus atributos.

                """ + "Contenido de otra seccion sin relacion.\\n\\n".repeat(30) + """
                Escenario V: Herencia

                La sobrescritura cambia la implementacion heredada y la jerarquia relaciona clases.
                """;

        String indexed = CurriculumSourceIndex.selectRelevantFragments(text, tree);

        assertTrue(indexed.contains("Unidad curricular: Fundamentos"));
        assertTrue(indexed.contains("Unidad curricular: Herencia"));
        assertTrue(indexed.contains("atributos"));
        assertTrue(indexed.contains("sobrescritura"));
    }
}
