package com.minerva.aiservice.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.minerva.aiservice.service.OllamaClient;
import com.minerva.aiservice.service.PdfTextExtractor;

@RestControllerAdvice
public class AiExceptionHandler {

    @ExceptionHandler(OllamaClient.OllamaUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleOllamaUnavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "Ollama is unavailable.");
    }

    @ExceptionHandler(OllamaClient.InvalidModelResponseException.class)
    public ResponseEntity<Map<String, String>> handleInvalidModelResponse() {
        return error(HttpStatus.BAD_GATEWAY, "The model returned an invalid response.");
    }

    @ExceptionHandler(PdfTextExtractor.InvalidPdfException.class)
    public ResponseEntity<Map<String, String>> handleInvalidPdf(PdfTextExtractor.InvalidPdfException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    @ExceptionHandler(PdfTextExtractor.NoExtractableTextException.class)
    public ResponseEntity<Map<String, String>> handleNoExtractableText(
            PdfTextExtractor.NoExtractableTextException exception) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage());
    }

    @ExceptionHandler(PdfTextExtractor.PdfTextTooLargeException.class)
    public ResponseEntity<Map<String, String>> handleExtractedTextTooLarge(
            PdfTextExtractor.PdfTextTooLargeException exception) {
        return error(HttpStatusCode.valueOf(413), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, String>> handleInvalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "Request body is invalid.");
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
