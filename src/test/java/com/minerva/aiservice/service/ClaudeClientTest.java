package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Base64;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ClaudeClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsAnthropicStructuredOutputRequestAndReturnsTextBlock() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> apiVersion = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            apiVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] response = """
                    {"content":[{"type":"text","text":"{\\"units\\":[]}"}],"stop_reason":"end_turn"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ClaudeClient client = new ClaudeClient(
                    objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-api-key",
                    "2026-10-21",
                    "claude-sonnet-4-5-20250929",
                    Duration.ofSeconds(5),
                    1024);
            String result = client.generateStructured(
                    "system instructions",
                    "study plan text",
                    Map.of("type", "object",
                            "properties", Map.of("name", Map.of(
                                    "type", "string", "minLength", 1))));

            assertEquals("{\"units\":[]}", result);
            assertEquals("test-api-key", apiKey.get());
            assertEquals("2026-10-21", apiVersion.get());

            JsonNode sent = objectMapper.readTree(requestBody.get());
            assertEquals("claude-sonnet-4-5-20250929", sent.path("model").asString());
            assertEquals("system instructions", sent.path("system").asString());
            assertEquals("study plan text",
                    sent.path("messages").get(0).path("content").asString());
            assertEquals("json_schema",
                    sent.path("output_config").path("format").path("type").asString());
            assertTrue(sent.path("output_config").path("format").has("schema"));
            assertTrue(sent.at("/output_config/format/schema/properties/name/minLength")
                    .isMissingNode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsNativePdfDocumentBlockToClaude() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            request.set(objectMapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"content":[{"type":"text","text":"{\\"units\\":[]}"}],"stop_reason":"end_turn"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ClaudeClient client = new ClaudeClient(
                    objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-api-key",
                    "2026-10-21",
                    "claude-sonnet-4-5-20250929",
                    Duration.ofSeconds(5),
                    1024);
            byte[] pdf = new byte[]{37, 80, 68, 70};

            assertEquals("{\"units\":[]}", client.generateStructuredWithPdf(
                    "system",
                    "Analyze the attached study plan.",
                    pdf,
                    Map.of("type", "object", "properties", Map.of())));
            assertTrue(client.supportsNativePdfInput());
            assertEquals("document",
                    request.get().at("/messages/0/content/0/type").asString());
            assertEquals("application/pdf",
                    request.get().at("/messages/0/content/0/source/media_type").asString());
            assertEquals(Base64.getEncoder().encodeToString(pdf),
                    request.get().at("/messages/0/content/0/source/data").asString());
            assertEquals("Analyze the attached study plan.",
                    request.get().at("/messages/0/content/1/text").asString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsTruncatedStructuredOutputInsteadOfReturningPartialJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] response = """
                    {"content":[{"type":"text","text":"{\\"units\\":["}],"stop_reason":"max_tokens"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ClaudeClient client = new ClaudeClient(
                    objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-api-key",
                    "2026-10-21",
                    "claude-sonnet-4-5-20250929",
                    Duration.ofSeconds(5),
                    64);

            org.junit.jupiter.api.Assertions.assertThrows(
                    AiModelClient.ModelOutputTruncatedException.class,
                    () -> client.generateStructured("system", "source", Map.of()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsRejectedAnthropicRequestsSeparatelyFromProviderOutages() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/messages", exchange -> {
            byte[] response = """
                    {"type":"error","error":{"type":"authentication_error","message":"invalid key"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ClaudeClient client = new ClaudeClient(
                    objectMapper,
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "test-api-key",
                    "2026-10-21",
                    "claude-sonnet-4-5-20250929",
                    Duration.ofSeconds(5),
                    1024);

            var exception = org.junit.jupiter.api.Assertions.assertThrows(
                    AiModelClient.ProviderRequestException.class,
                    () -> client.generateStructured("system", "source", Map.of()));
            assertEquals(401, exception.getStatusCode());
        } finally {
            server.stop(0);
        }
    }
}
