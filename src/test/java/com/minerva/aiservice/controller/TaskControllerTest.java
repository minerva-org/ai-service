package com.minerva.aiservice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.minerva.aiservice.dto.TaskDtos;
import com.minerva.aiservice.service.TaskStoreService;

class TaskControllerTest {
    private TaskStoreService store;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = mock(TaskStoreService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new TaskController(store))
                .build();
    }

    @Test
    void createsCurriculumTaskWithAcceptedStatus() throws Exception {
        when(store.create(any(TaskDtos.CreateRequest.class)))
                .thenReturn(new TaskDtos.CreateResponse("task-1"));

        mvc.perform(post("/v1/tareas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tipo": "CURRICULUM_GENERATE",
                                  "datos": {
                                    "grupoId": "GRP-1",
                                    "arbol": {
                                      "tipo": "materia",
                                      "nombre": "Materia",
                                      "hijos": []
                                    },
                                    "fuentes": [],
                                    "fuentesProcesadas": [],
                                    "modoFusion": "conservadora"
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.tareaId").value("task-1"));

        verify(store).create(any(TaskDtos.CreateRequest.class));
    }

    @Test
    void getsTaskStatus() throws Exception {
        when(store.get("task-1"))
                .thenReturn(new TaskDtos.StatusResponse(
                        "task-1",
                        "CURRICULUM_GENERATE",
                        "COMPLETADA",
                        "Generacion terminada",
                        null,
                        null));

        mvc.perform(get("/v1/tareas/task-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADA"));
    }

    @Test
    void returnsNotFoundForMissingTask() throws Exception {
        when(store.get("missing"))
                .thenThrow(new TaskStoreService.TaskNotFoundException(
                        "La tarea no existe."));

        mvc.perform(get("/v1/tareas/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletesTaskAndReturnsNoContent() throws Exception {
        mvc.perform(delete("/v1/tareas/task-1"))
                .andExpect(status().isNoContent());

        verify(store).delete("task-1");
    }
}
