package com.minerva.aiservice.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minerva.aiservice.config.AiServiceProperties;
import com.minerva.aiservice.dto.HealthResponse;
import com.minerva.aiservice.service.OllamaClient;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final OllamaClient ollamaClient;
    private final AiServiceProperties properties;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        try {
            if (ollamaClient.isConfiguredModelAvailable()) {
                return ResponseEntity.ok(new HealthResponse("UP", properties.ollama().model()));
            }
        } catch (OllamaClient.OllamaUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("DOWN", properties.ollama().model()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("DOWN", properties.ollama().model()));
    }
}
