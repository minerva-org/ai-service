package com.minerva.aiservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minerva.aiservice.dto.MetricsAssistantRequest;
import com.minerva.aiservice.dto.MetricsAssistantResponse;
import com.minerva.aiservice.service.MetricsAssistantService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/metrics-assistant")
@RequiredArgsConstructor
public class MetricsAssistantController {

    private final MetricsAssistantService metricsAssistantService;

    @PostMapping
    public ResponseEntity<MetricsAssistantResponse> consult(
            @Valid @RequestBody MetricsAssistantRequest request) {
        return ResponseEntity.ok(metricsAssistantService.consult(request));
    }
}
