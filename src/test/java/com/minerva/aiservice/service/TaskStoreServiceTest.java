package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.minerva.aiservice.dto.CurriculumGenerationRequest;
import com.minerva.aiservice.dto.CurriculumGenerationResponse;
import com.minerva.aiservice.dto.TaskDtos;

import tools.jackson.databind.ObjectMapper;

class TaskStoreServiceTest {
    private CurriculumGenerationService curriculumService;
    private TaskStoreService store;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        curriculumService = mock(CurriculumGenerationService.class);
        store = new TaskStoreService(curriculumService, mapper);
    }

    @AfterEach
    void tearDown() {
        store.destroy();
    }

    @Test
    void rejectsNullRequest() {
        assertThrows(
                TaskStoreService.InvalidTaskRequestException.class,
                () -> store.create(null));
    }

    @Test
    void rejectsUnsupportedTaskType() {
        var request = new TaskDtos.CreateRequest(
                "GENERAR_PREGUNTAS",
                mapper.createObjectNode());

        assertThrows(
                TaskStoreService.InvalidTaskRequestException.class,
                () -> store.create(request));
    }

    @Test
    void rejectsMissingArbol() {
        var datos = mapper.createObjectNode();
        datos.put("grupoId", "GRP-1");
        var request = new TaskDtos.CreateRequest(
                "CURRICULUM_GENERATE",
                datos);

        assertThrows(
                TaskStoreService.InvalidTaskRequestException.class,
                () -> store.create(request));
    }

    @Test
    void rejectsMissingGrupoId() {
        var datos = mapper.createObjectNode();
        var arbol = datos.putObject("arbol");
        arbol.put("tipo", "materia");
        arbol.put("nombre", "Materia");
        arbol.putArray("hijos");
        var request = new TaskDtos.CreateRequest(
                "CURRICULUM_GENERATE",
                datos);

        assertThrows(
                TaskStoreService.InvalidTaskRequestException.class,
                () -> store.create(request));
    }

    @Test
    void acceptsValidTaskAndCompletesAsynchronously() throws Exception {
        var arbol = mapper.createObjectNode();
        arbol.put("tipo", "materia");
        arbol.put("nombre", "Materia");
        arbol.putArray("hijos");

        var datos = mapper.createObjectNode();
        datos.put("grupoId", "GRP-1");
        datos.set("arbol", arbol);
        datos.putArray("fuentes");
        datos.putArray("fuentesProcesadas");
        datos.put("modoFusion", "conservadora");

        var response = new CurriculumGenerationResponse(
                "GRP-1",
                false,
                arbol,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(curriculumService.generate(
                any(CurriculumGenerationRequest.class),
                any(org.springframework.web.multipart.MultipartFile[].class),
                any(Consumer.class)))
                .thenReturn(response);

        var created = store.create(new TaskDtos.CreateRequest(
                "CURRICULUM_GENERATE",
                datos));

        assertNotNull(created.tareaId());
        assertThrows(
                TaskStoreService.TaskNotFoundException.class,
                () -> store.get("missing"));

        TaskDtos.StatusResponse status = null;
        for (int i = 0; i < 200; i++) {
            status = store.get(created.tareaId());
            if ("COMPLETADA".equals(status.estado())
                    || "ERROR".equals(status.estado())) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }

        assertEquals("COMPLETADA", status.estado());
        assertEquals("Generacion terminada", status.progreso());
    }

    @Test
    void marksFatalGenerationResponseAsError() throws Exception {
        var arbol = mapper.createObjectNode();
        arbol.put("tipo", "materia");
        arbol.put("nombre", "Materia");
        arbol.putArray("hijos");

        var datos = mapper.createObjectNode();
        datos.put("grupoId", "GRP-1");
        datos.set("arbol", arbol);
        datos.putArray("fuentes");
        datos.putArray("fuentesProcesadas");

        var response = new CurriculumGenerationResponse(
                "GRP-1",
                false,
                arbol,
                List.of(),
                List.of(),
                List.of(),
                List.of(new CurriculumGenerationResponse.ErrorDetalle(
                        "FUENTE_SIN_CONTENIDO",
                        "No se pudo obtener contenido utilizable.",
                        true)));
        when(curriculumService.generate(
                any(CurriculumGenerationRequest.class),
                any(org.springframework.web.multipart.MultipartFile[].class),
                any(Consumer.class)))
                .thenReturn(response);

        var created = store.create(new TaskDtos.CreateRequest(
                "CURRICULUM_GENERATE",
                datos));

        TaskDtos.StatusResponse status = null;
        for (int i = 0; i < 200; i++) {
            status = store.get(created.tareaId());
            if ("ERROR".equals(status.estado())) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }

        assertEquals("ERROR", status.estado());
        assertEquals(
                "No se pudo obtener contenido utilizable.",
                status.error());
        assertEquals(null, status.resultado());
    }

    @Test
    void preservesErrorStatusWhenGenerationFails() throws Exception {
        var arbol = mapper.createObjectNode();
        arbol.put("tipo", "materia");
        arbol.put("nombre", "Materia");
        arbol.putArray("hijos");

        var datos = mapper.createObjectNode();
        datos.put("grupoId", "GRP-1");
        datos.set("arbol", arbol);
        datos.putArray("fuentes");
        datos.putArray("fuentesProcesadas");

        when(curriculumService.generate(
                any(CurriculumGenerationRequest.class),
                any(org.springframework.web.multipart.MultipartFile[].class),
                any(Consumer.class)))
                .thenThrow(new IllegalStateException("Ollama no disponible"));

        var created = store.create(new TaskDtos.CreateRequest(
                "CURRICULUM_GENERATE",
                datos));

        TaskDtos.StatusResponse status = null;
        for (int i = 0; i < 200; i++) {
            status = store.get(created.tareaId());
            if ("ERROR".equals(status.estado())) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }

        assertEquals("ERROR", status.estado());
        assertEquals("Ollama no disponible", status.error());
        assertEquals(null, status.resultado());
    }

    @Test
    void deleteMakesTaskUnavailable() {
        var datos = mapper.createObjectNode();
        var arbol = datos.putObject("arbol");
        arbol.put("tipo", "materia");
        arbol.put("nombre", "Materia");
        arbol.putArray("hijos");
        datos.put("grupoId", "GRP-1");
        datos.putArray("fuentes");
        datos.putArray("fuentesProcesadas");

        var created = store.create(new TaskDtos.CreateRequest(
                "CURRICULUM_GENERATE",
                datos));
        store.delete(created.tareaId());

        assertThrows(
                TaskStoreService.TaskNotFoundException.class,
                () -> store.get(created.tareaId()));
    }
}
