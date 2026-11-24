package com.minerva.aiservice.service;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Primary;

@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "claude")
@Primary
public class ClaudeClient implements AiModelClient {
    private static final Logger LOGGER =
            Logger.getLogger(ClaudeClient.class.getName());

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;
    private final int maxOutputTokens;

    public ClaudeClient(
            ObjectMapper objectMapper,
            @Value("${ai.claude.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${ai.claude.api-key:}") String apiKey,
            @Value("${ai.claude.api-version:2026-10-21}") String apiVersion,
            @Value("${ai.claude.model:claude-sonnet-4-5-20250929}") String model,
            @Value("${ai.claude.timeout:180s}") Duration timeout,
            @Value("${ai.claude.max-output-tokens:16384}") int maxOutputTokens) {
        Assert.hasText(apiKey, "ANTHROPIC_API_KEY debe configurarse al usar Claude.");
        Assert.hasText(model, "ai.claude.model debe configurarse al usar Claude.");
        Assert.isTrue(maxOutputTokens > 0, "ai.claude.max-output-tokens debe ser positivo.");
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", apiVersion)
                .build();
    }

    @Override
    public String provider() {
        return "claude";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfiguredModelAvailable() {
        try {
            JsonNode response = restClient.get()
                    .uri("/v1/models?limit=1000")
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || !response.path("data").isArray()) {
                return false;
            }
            for (JsonNode availableModel : response.path("data")) {
                if (model.equals(availableModel.path("id").asString())) {
                    return true;
                }
            }
            return false;
        } catch (RestClientException exception) {
            throw providerException(exception);
        }
    }

    @Override
    public <T> T generateJson(
            String systemPrompt,
            String userPrompt,
            Class<T> responseType) {
        String content = generate(systemPrompt, userPrompt, null);
        try {
            return objectMapper.readValue(content, responseType);
        } catch (JacksonException exception) {
            throw new AiModelClient.InvalidModelResponseException(
                    "Claude devolvió JSON inválido.", exception);
        }
    }

    @Override
    public String generateStructured(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> schema) {
        return generate(systemPrompt, userPrompt, schema);
    }

    @Override
    public boolean supportsNativePdfInput() {
        return true;
    }

    @Override
    public String generateStructuredWithPdf(
            String systemPrompt,
            String userPrompt,
            byte[] pdf,
            Map<String, Object> schema) {
        String encodedPdf = java.util.Base64.getEncoder().encodeToString(pdf);
        List<Map<String, Object>> content = List.of(
                Map.of(
                        "type", "document",
                        "source", Map.of(
                                "type", "base64",
                                "media_type", "application/pdf",
                                "data", encodedPdf)),
                Map.of("type", "text", "text", userPrompt));
        return generate(systemPrompt, content, schema);
    }

    private String generate(
            String systemPrompt,
            Object userContent,
            Map<String, Object> schema) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("max_tokens", maxOutputTokens);
        request.put("system", systemPrompt);
        request.put("messages", List.of(Map.of(
                "role", "user",
                "content", userContent)));
        if (schema != null) {
            request.put("output_config", Map.of(
                    "format", Map.of(
                            "type", "json_schema",
                            "schema", transformSchemaValue(schema))));
        }

        long startedAt = System.nanoTime();
        try {
            JsonNode response = restClient.post()
                    .uri("/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                throw new AiModelClient.InvalidModelResponseException(
                        "Claude devolvió una respuesta vacía.");
            }
            JsonNode stopReason = response.path("stop_reason");
            if (stopReason.isString() && "max_tokens".equals(stopReason.asString())) {
                throw new AiModelClient.ModelOutputTruncatedException(
                        "Claude alcanzó el límite de tokens de salida.", "");
            }
            for (JsonNode block : response.path("content")) {
                if ("text".equals(block.path("type").asString())
                        && block.path("text").isString()) {
                    String content = block.path("text").asString();
                    LOGGER.log(Level.INFO,
                            "CLAUDE_RESPUESTA: modelo={0}, respuestaChars={1}, duracionMs={2}",
                            new Object[]{model, content.length(),
                                    (System.nanoTime() - startedAt) / 1_000_000});
                    return content;
                }
            }
            throw new AiModelClient.InvalidModelResponseException(
                    "Claude no devolvió un bloque de texto.");
        } catch (RestClientException exception) {
            throw providerException(exception);
        }
    }

    private static AiModelClient.ProviderUnavailableException providerException(
            RestClientException exception) {
        if (hasTimeoutCause(exception)) {
            return new AiModelClient.ProviderTimeoutException(
                    "La solicitud a Claude excedió el tiempo de espera.", exception);
        }
        if (exception instanceof RestClientResponseException responseException) {
            int statusCode = responseException.getStatusCode().value();
            if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
                return new AiModelClient.ProviderUnavailableException(
                        "La API de Claude no está disponible.", exception);
            }
            throw new AiModelClient.ProviderRequestException(statusCode, exception);
        }
        LOGGER.log(Level.WARNING, "CLAUDE_API_ERROR: " + exception.getMessage());
        return new AiModelClient.ProviderUnavailableException(
                "La API de Claude no está disponible o rechazó la solicitud.", exception);
    }

    private static boolean hasTimeoutCause(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Object transformSchemaValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> transformed = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key
                        && !"minLength".equals(key)) {
                    transformed.put(key, transformSchemaValue(entry.getValue()));
                }
            }
            return transformed;
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(ClaudeClient::transformSchemaValue)
                    .toList();
        }
        return value;
    }
}
