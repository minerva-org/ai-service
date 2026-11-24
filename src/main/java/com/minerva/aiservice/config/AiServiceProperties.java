package com.minerva.aiservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

@Validated
@ConfigurationProperties(prefix = "ai")
public record AiServiceProperties(
        @Valid @NotNull Ollama ollama,
        @Valid @NotNull Security security,
        @Valid @NotNull Limits limits) {

    public record Ollama(
            @NotBlank String baseUrl,
            @NotBlank String model,
            @NotNull Duration timeout) {
    }

    public record Security(
            @NotBlank @Size(min = 32) String apiKey) {
    }

    public record Limits(
            @Positive long maxPdfBytes,
            @Positive int maxExtractedCharacters) {
    }

}
