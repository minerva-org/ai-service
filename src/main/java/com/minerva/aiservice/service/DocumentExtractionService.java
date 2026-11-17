package com.minerva.aiservice.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.DocumentExtractionResponse;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DocumentExtractionService {

    private final PdfTextExtractor pdfTextExtractor;
    private final OllamaClient ollamaClient;
    private final Validator validator;

    public DocumentExtractionResponse extract(MultipartFile pdfContent) {
        String text = pdfTextExtractor.extract(pdfContent);
        String systemPrompt = """
                Desglosa el texto fuente en una jerarquía académica de unidad, tema y concepto.
                Devuelve únicamente JSON que cumpla el esquema solicitado.
                Basa cada elemento en el texto recibido; no inventes contenido.
                Si el texto no contiene una jerarquía académica identificable, no inventes unidades.
                """;

        DocumentExtractionResponse result = ollamaClient.generateJson(
                systemPrompt,
                text,
                DocumentExtractionResponse.class);
        ensureValid(result);
        return result;
    }

    private void ensureValid(DocumentExtractionResponse result) {
        if (!validator.validate(result).isEmpty()) {
            throw new OllamaClient.InvalidModelResponseException(
                    "Model output did not match the document hierarchy schema.");
        }
    }
}
