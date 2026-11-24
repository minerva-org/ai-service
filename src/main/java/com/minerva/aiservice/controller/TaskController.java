package com.minerva.aiservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.minerva.aiservice.dto.TaskDtos;
import com.minerva.aiservice.service.TaskStoreService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/tareas")
@RequiredArgsConstructor
public class TaskController {
    private final TaskStoreService taskStore;

    @PostMapping
    public ResponseEntity<TaskDtos.CreateResponse> create(
            @RequestBody TaskDtos.CreateRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(taskStore.create(request));
        } catch (TaskStoreService.InvalidTaskRequestException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    exception.getMessage());
        }
    }

    @GetMapping("/{tareaId}")
    public TaskDtos.StatusResponse get(
            @PathVariable String tareaId) {
        try {
            return taskStore.get(tareaId);
        } catch (TaskStoreService.TaskNotFoundException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    exception.getMessage());
        }
    }

    @DeleteMapping("/{tareaId}")
    public ResponseEntity<Void> delete(
            @PathVariable String tareaId) {
        taskStore.delete(tareaId);
        return ResponseEntity.noContent().build();
    }
}
