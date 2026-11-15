package com.minerva.aiservice.service;

import java.util.List;
import java.util.Map;
import java.net.http.HttpClient;

import org.springframework.stereotype.Service;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.minerva.aiservice.config.AiServiceProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
@Service
public class OllamaClient {

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
            throw new OllamaUnavailableException(exception);
        }
    }

    public <T> T generateJson(String systemPrompt, String userPrompt, Class<T> responseType) {
        Map<String, Object> request = Map.of(
                "model", properties.ollama().model(),
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)),
                "format", "json",
                "stream", false,
                "think", false);

        final OllamaChatResponse response;
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .body(request)
                    .retrieve()
                    .body(OllamaChatResponse.class);
        } catch (RestClientException exception) {
            throw new OllamaUnavailableException(exception);
        }

        if (response == null || response.message() == null || response.message().content() == null) {
            throw new InvalidModelResponseException("Ollama returned an empty chat response.");
        }
        try {
            return objectMapper.readValue(response.message().content(), responseType);
        } catch (JacksonException exception) {
            throw new InvalidModelResponseException("Ollama returned invalid JSON.", exception);
        }
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

    public static class InvalidModelResponseException extends RuntimeException {
        public InvalidModelResponseException(String message) {
            super(message);
        }

        public InvalidModelResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
