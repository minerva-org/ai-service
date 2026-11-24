package com.minerva.aiservice.service;

import java.util.Map;

public interface AiModelClient {
    String provider();

    String model();

    boolean isConfiguredModelAvailable();

    <T> T generateJson(String systemPrompt, String userPrompt, Class<T> responseType);

    String generateStructured(
            String systemPrompt,
            String userPrompt,
            Map<String, Object> schema);

    default boolean supportsNativePdfInput() {
        return false;
    }

    default String generateStructuredWithPdf(
            String systemPrompt,
            String userPrompt,
            byte[] pdf,
            Map<String, Object> schema) {
        throw new UnsupportedOperationException(
                "Este proveedor no admite entrada PDF nativa.");
    }

    class ProviderException extends RuntimeException {
        public ProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class ProviderUnavailableException extends ProviderException {
        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class ProviderRequestException extends ProviderException {
        private final int statusCode;

        public ProviderRequestException(int statusCode, Throwable cause) {
            super("El proveedor rechazó la solicitud (HTTP " + statusCode + ").", cause);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }

    class ProviderTimeoutException extends ProviderUnavailableException {
        public ProviderTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class InvalidModelResponseException extends RuntimeException {
        public InvalidModelResponseException(String message) {
            super(message);
        }

        public InvalidModelResponseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class ModelOutputTruncatedException extends RuntimeException {
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
