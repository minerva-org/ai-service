package com.minerva.aiservice.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import com.sun.net.httpserver.HttpServer;

import com.minerva.aiservice.config.AiServiceProperties;
import com.minerva.aiservice.dto.DocumentLabRequest;
import com.minerva.aiservice.dto.DocumentLabRequest.SourceType;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class DocumentLaboratoryServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final PdfTextExtractor extractor = mock(PdfTextExtractor.class);
    private final MockMultipartFile file = new MockMultipartFile("file", "plan.pdf",
            "application/pdf", new byte[]{1});
    private final AiServiceProperties properties = new AiServiceProperties(
            new AiServiceProperties.Ollama("http://ollama.test", "qwen3:8b", Duration.ofSeconds(180)),
            new AiServiceProperties.Security("a".repeat(32)),
            new AiServiceProperties.Limits(10485760, 30000, 4000000));
    private DocumentLaboratoryService service;

    @BeforeEach
    void setUp() {
        service = new DocumentLaboratoryService(extractor, ollama, mapper, properties);
        when(ollama.model()).thenReturn("qwen3:8b");
        when(extractor.extract(file)).thenReturn("Unidad Algebra. Tema Ecuaciones.");
    }

    @Test
    void composesThreeLayersAndSourceWithoutMutatingDefaults() {
        var defaults = service.defaults();
        var prompts = new com.minerva.aiservice.dto.ExtractionPrompts("ROL", "EXTRACCION", "CONTRATO");
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn("""
                {"units":[{"name":"Algebra","topics":[{"name":"Ecuaciones","concepts":[{"name":"Igualdad"}]}]}]}
                """);

        var result = service.extract(file, new DocumentLabRequest(SourceType.STUDY_PLAN, prompts));

        assertTrue(result.isValid());
        assertEquals("qwen3:8b", result.model());
        assertNotNull(result.tree());
        verify(ollama).generateStructured(
                argThat(system -> system.contains("ROL") && system.contains("EXTRACCION")
                        && system.contains("CONTRATO") && system.contains("plan de estudio")
                        && system.contains("mantén cada unidad distinta separada")),
                contains("<document>\nUnidad Algebra."), eq(DocumentExtractionSchema.schema()));
        assertEquals(defaults, service.defaults());
        assertTrue(defaults.prompts().instructions()
                .contains("no fusiones unidades distintas"));
        assertTrue(defaults.prompts().outputContract().contains("Unidad 2"));
    }

    @Test
    void returnsRawOutputAndErrorsInsteadOfRepairingMalformedOrWrongShapeJson() {
        for (String raw : new String[]{
                "```json\n{\"units\":[]}\n```",
                "{\"units\":[]} texto extra",
                "{\"units\":null}",
                "{\"units\":[{\"name\":\" \",\"topics\":[]}]}",
                "{\"units\":[null]}",
                "{\"units\":[],\"explanation\":\"extra\"}"}) {
            when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn(raw);
            var result = service.extract(file,
                    new DocumentLabRequest(SourceType.SOURCE_MATERIAL, service.defaults().prompts()));
            assertFalse(result.isValid(), raw);
            assertNull(result.tree(), raw);
            assertEquals(raw, result.rawOutput());
            assertFalse(result.validationErrors().isEmpty());
        }
    }

    @Test
    void supportsNoIdentifiableContentAndMaterialSourceMode() {
        when(ollama.generateStructured(anyString(), anyString(), anyMap())).thenReturn("{\"units\":[]}");
        var result = service.extract(file,
                new DocumentLabRequest(SourceType.SOURCE_MATERIAL, service.defaults().prompts()));
        assertTrue(result.isValid());
        assertTrue(result.tree().get("units").isEmpty());
        verify(ollama).generateStructured(contains("material didactico"), anyString(), anyMap());
    }

    @Test
    void sendsJsonSchemaAndKeepsExistingJsonMode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<JsonNode> requests = new CopyOnWriteArrayList<>();
        server.createContext("/api/chat", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"message":{"role":"assistant","content":"{\\"units\\":[]}"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var localProperties = new AiServiceProperties(
                    new AiServiceProperties.Ollama("http://127.0.0.1:" + server.getAddress().getPort(),
                            "qwen3:8b", Duration.ofSeconds(5)),
                    properties.security(), properties.limits());
            OllamaClient client = new OllamaClient(localProperties, mapper);
            assertEquals("{\"units\":[]}", client.generateStructured("system", "source",
                    DocumentExtractionSchema.schema()));
            assertTrue(client.generateJson("system", "source", Map.class).containsKey("units"));
            assertEquals(2, requests.size());
            assertEquals("qwen3:8b", requests.getFirst().get("model").asString());
            assertEquals("object", requests.getFirst().at("/format/type").asString());
            assertFalse(requests.getFirst().at("/format/additionalProperties").asBoolean());
            assertEquals("array", requests.getFirst()
                    .at("/format/properties/units/items/properties/topics/type").asString());
            assertFalse(requests.getFirst().get("stream").asBoolean());
            assertEquals("json", requests.getLast().get("format").asString());
        } finally {
            server.stop(0);
        }
    }
}
