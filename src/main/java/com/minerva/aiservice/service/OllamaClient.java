package com.minerva.aiservice.service;

import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.minerva.aiservice.config.AiServiceProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
@Service
public class OllamaClient {

    private static final Logger LOGGER =
            Logger.getLogger(OllamaClient.class.getName());
    private final AiServiceProperties properties;
    private final ObjectMapper objectMapper;
        private final RestClient restClient;

        public OllamaClient(AiServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.ollama().timeout());
        this.restClient = RestClient.builder()
            .baseUrl(properties.ollama().baseUrl())
            .requestFactory(requestFactory)
            .build();
        }

    public boolean isConfiguredModelAvailable() {
        try {
            OllamaTagsResponse response = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(OllamaTagsResponse.class);
            return response != null
                    && response.models() != null
                    && response.models().stream().anyMatch(model -> properties.ollama().model().equals(model.name()));
        } catch (RestClientException exception) {
            if (hasTimeoutCause(exception)) {
                throw new OllamaTimeoutException(exception);
            }
            throw new OllamaUnavailableException(exception);
        }
    }

    public <T> T generateJson(String systemPrompt, String userPrompt, Class<T> responseType) {
        String content = generateContent(systemPrompt, userPrompt, "json");
        try {
            return objectMapper.readValue(content, responseType);
        } catch (JacksonException exception) {
            throw new InvalidModelResponseException("Ollama returned invalid JSON.", exception);
        }
    }

    public String generateStructured(String systemPrompt, String userPrompt, Map<String, Object> schema) {
        return generateContent(systemPrompt, userPrompt, schema);
    }

    private String generateContent(String systemPrompt, String userPrompt, Object format) {
        Map<String, Object> request = Map.of(
                "model", properties.ollama().model(),
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)),
                "format", format,
                "stream", false,
                "think", false,
                "options", Map.of(
                        "temperature", 0.1,
                        "num_ctx", 8192,
                        "num_predict", 2048));

        final OllamaChatResponse response;
        long startedAt = System.nanoTime();
        LOGGER.log(Level.INFO,
                "OLLAMA_SOLICITUD: modelo={0}, promptChars={1}, systemChars={2}, formato={3}",
                new Object[]{properties.ollama().model(), userPrompt.length(), systemPrompt.length(),
                        format instanceof Map ? "json_schema" : String.valueOf(format)});
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .body(request)
                    .retrieve()
                    .body(OllamaChatResponse.class);
        } catch (RestClientException exception) {
            if (hasTimeoutCause(exception)) {
                throw new OllamaTimeoutException(exception);
            }
            throw new OllamaUnavailableException(exception);
        }

        if (response == null || response.message() == null || response.message().content() == null) {
            throw new InvalidModelResponseException("Ollama returned an empty chat response.");
        }
        String content = response.message().content();
        LOGGER.log(Level.INFO,
                "OLLAMA_RESPUESTA: modelo={0}, respuestaChars={1}, duracionMs={2}",
                new Object[]{properties.ollama().model(), content.length(),
                        (System.nanoTime() - startedAt) / 1_000_000});
        return content;
    }

    private boolean hasTimeoutCause(Throwable cause) {
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

    private record OllamaTagsResponse(List<OllamaModel> models) {
    }

    private record OllamaModel(String name) {
    }

    private record OllamaChatResponse(OllamaMessage message) {
    }

    private record OllamaMessage(String role, String content) {
    }

    public static class OllamaUnavailableException extends RuntimeException {
        public OllamaUnavailableException(Throwable cause) {
            super("Ollama is unavailable.", cause);
        }
    }

    public static class OllamaTimeoutException extends RuntimeException {
        public OllamaTimeoutException(Throwable cause) {
            super("Ollama request timed out.", cause);
        }
    }

    public static class InvalidModelResponseException extends RuntimeException {
        public InvalidModelResponseException(String message) {
            super(message);
        }

        public InvalidModelResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
