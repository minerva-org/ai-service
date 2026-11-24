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

    /**
     * Defaults de generacion. Ollama trunca la respuesta al alcanzar
     * num_predict; 4096 era insuficiente para arboles grandes con muchas
     * descripciones. 8192 cubre la mayoria de los casos. Si AiServiceProperties
     * expone numPredict/numCtx, se respetaran esos valores (ver resolve*).
     */
    private static final int DEFAULT_NUM_PREDICT = 8192;
    private static final int DEFAULT_NUM_CTX     = 32768;

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
        int numPredict = resolveNumPredict();
        int numCtx = resolveNumCtx();

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
                        "num_ctx", numCtx,
                        "num_predict", numPredict));

        final OllamaChatResponse response;
        long startedAt = System.nanoTime();
        LOGGER.log(Level.INFO,
                "OLLAMA_SOLICITUD: modelo={0}, promptChars={1}, systemChars={2}, formato={3}, numPredict={4}, numCtx={5}",
                new Object[]{properties.ollama().model(), userPrompt.length(), systemPrompt.length(),
                        format instanceof Map ? "json_schema" : String.valueOf(format),
                        numPredict, numCtx});
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
        LOGGER.log(Level.INFO,
                "OLLAMA_ESTADO: modelo={0}, done={1}, doneReason={2}",
                new Object[]{properties.ollama().model(), response.done(),
                        response.doneReason()});

        // Fix 2: si Ollama trunco por num_predict, NO es un JSON invalido:
        // es un caso operativo distinto que el llamador puede reintentar.
        if (Boolean.TRUE.equals(response.done())
                && "length".equalsIgnoreCase(response.doneReason())) {
            LOGGER.log(Level.WARNING,
                    "OLLAMA_TRUNCADO: modelo={0}, numPredict={1}, respuestaChars={2}",
                    new Object[]{properties.ollama().model(), numPredict, content.length()});
            throw new ModelOutputTruncatedException(
                    "Ollama trunco la respuesta (num_predict=" + numPredict + ").",
                    content);
        }

        LOGGER.log(Level.INFO,
                "OLLAMA_RESPUESTA_RAW: respuesta={0}",
                content);
        return content;
    }

    /**
     * Devuelve num_predict configurado en AiServiceProperties si existe; si no,
     * usa el default. Cuando añadas el campo en propiedades, sustituye el
     * comentario por la llamada real (ej. properties.ollama().numPredict()).
     */
    private int resolveNumPredict() {
        // Integer configured = properties.ollama().numPredict();
        // if (configured != null && configured > 0) return configured;
        return DEFAULT_NUM_PREDICT;
    }

    private int resolveNumCtx() {
        // Integer configured = properties.ollama().numCtx();
        // if (configured != null && configured > 0) return configured;
        return DEFAULT_NUM_CTX;
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

    private record OllamaChatResponse(
            OllamaMessage message,
            Boolean done,
            String doneReason) {
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

    /**
     * Nueva excepcion: respuesta del modelo truncada por num_predict.
     * Semantica distinta de InvalidModelResponseException (no es JSON malformado,
     * es salida incompleta). Los llamadores pueden reintentar con menos operaciones.
     */
    public static class ModelOutputTruncatedException extends RuntimeException {
        private final String partialContent;

        public ModelOutputTruncatedException(String message, String partialContent) {
            super(message);
            this.partialContent = partialContent == null ? "" : partialContent;
        }

        public String getPartialContent() {
            return partialContent;
        }
    }
}