package com.minerva.aiservice.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minerva.aiservice.dto.HealthResponse;
import com.minerva.aiservice.service.AiModelClient;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class HealthController {

    private final AiModelClient aiModelClient;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        try {
            if (aiModelClient.isConfiguredModelAvailable()) {
                return ResponseEntity.ok(new HealthResponse("UP", aiModelClient.model()));
            }
        } catch (AiModelClient.ProviderException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthResponse("DOWN", aiModelClient.model()));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("DOWN", aiModelClient.model()));
    }

    @GetMapping("/v1/salud")
    public ResponseEntity<Map<String, Object>> healthV1() {
        try {
            if (aiModelClient.isConfiguredModelAvailable()) {
                return ResponseEntity.ok(Map.of(
                        "motor", aiModelClient.provider(),
                        "ok", true,
                        "modelo", aiModelClient.model()));
            }
        } catch (AiModelClient.ProviderException ignored) {
            return ResponseEntity.ok(Map.of(
                    "motor", aiModelClient.provider(),
                    "ok", false,
                    "modelo", aiModelClient.model()));
        }
        return ResponseEntity.ok(Map.of(
                "motor", aiModelClient.provider(),
                "ok", false,
                "modelo", aiModelClient.model()));
    }
}
