package com.minerva.aiservice.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.DocumentExtractionResponse;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class DocumentExtractionService {

    private final PdfTextExtractor pdfTextExtractor;
    private final OllamaClient ollamaClient;
    private final Validator validator;
    private final ObjectMapper objectMapper;

    public DocumentExtractionResponse extract(MultipartFile pdfContent) {
        String text = pdfTextExtractor.extract(pdfContent);
        String systemPrompt = """
                Desglosa el texto fuente en una jerarquía académica de unidad, tema y concepto.
                Devuelve únicamente JSON que cumpla el esquema solicitado.
                Basa cada elemento en el texto recibido; no inventes contenido.
                Si el texto no contiene una jerarquía académica identificable, no inventes unidades.
                """;

        String raw = ollamaClient.generateStructured(
                systemPrompt,
                "Documento fuente:\n<document>\n" + text + "\n</document>",
                DocumentExtractionSchema.schema());

        JsonNode tree;
        try {
            tree = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(raw);
        } catch (JacksonException exception) {
            throw new OllamaClient.InvalidModelResponseException(
                    "Ollama returned invalid JSON.", exception);
        }

        List<String> hierarchyErrors = DocumentExtractionSchema.validate(tree);
        if (!hierarchyErrors.isEmpty()) {
            throw new IncompleteHierarchyException(hierarchyErrors);
        }

        try {
            DocumentExtractionResponse result = objectMapper.readValue(raw, DocumentExtractionResponse.class);
            ensureValid(result);
            return result;
        } catch (JacksonException exception) {
            throw new OllamaClient.InvalidModelResponseException(
                    "Ollama returned invalid JSON.", exception);
        }
    }

    private void ensureValid(DocumentExtractionResponse result) {
        if (result.units() == null || result.units().isEmpty()) {
            throw new NoUsableHierarchyException(
                    "No se identifico contenido academico utilizable para una materia.");
        }
        if (!validator.validate(result).isEmpty()) {
            throw new OllamaClient.InvalidModelResponseException(
                    "Model output did not match the document hierarchy schema.");
        }
    }

    public static class NoUsableHierarchyException extends RuntimeException {
        public NoUsableHierarchyException(String message) {
            super(message);
        }
    }

    public static class IncompleteHierarchyException extends RuntimeException {
        private final List<String> validationErrors;

        public IncompleteHierarchyException(List<String> validationErrors) {
            super("La jerarquia extraida esta incompleta.");
            this.validationErrors = List.copyOf(validationErrors);
        }

        public List<String> getValidationErrors() {
            return validationErrors;
        }
    }
}
