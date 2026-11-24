package com.minerva.aiservice.controller;

import java.util.Map;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.minerva.aiservice.service.AiModelClient;
import com.minerva.aiservice.service.OllamaClient;
import com.minerva.aiservice.service.PdfTextExtractor;
import com.minerva.aiservice.service.DocumentTextExtractor;
import com.minerva.aiservice.service.DocumentExtractionService;
import com.minerva.aiservice.service.CurriculumGenerationService;

@RestControllerAdvice
public class AiExceptionHandler {
    private static final Logger LOGGER = Logger.getLogger(AiExceptionHandler.class.getName());

    @ExceptionHandler(OllamaClient.OllamaUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleOllamaUnavailable(
            OllamaClient.OllamaUnavailableException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_OLLAMA_UNAVAILABLE", exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "OLLAMA_UNAVAILABLE",
                "El servicio de inteligencia artificial no esta disponible.");
    }

    @ExceptionHandler(AiModelClient.ProviderUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleProviderUnavailable(
            AiModelClient.ProviderUnavailableException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_PROVIDER_UNAVAILABLE", exception);
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "AI_PROVIDER_UNAVAILABLE",
                "El servicio de inteligencia artificial no esta disponible.");
    }

    @ExceptionHandler(AiModelClient.ProviderRequestException.class)
    public ResponseEntity<Map<String, Object>> handleProviderRequestRejected(
            AiModelClient.ProviderRequestException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_PROVIDER_REQUEST_REJECTED", exception);
        return error(HttpStatus.BAD_GATEWAY,
                "AI_PROVIDER_REQUEST_REJECTED",
                "El proveedor de inteligencia artificial rechazó la solicitud.");
    }

    @ExceptionHandler(OllamaClient.OllamaTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleOllamaTimeout(
            OllamaClient.OllamaTimeoutException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_OLLAMA_TIMEOUT", exception);
        return error(HttpStatus.GATEWAY_TIMEOUT,
                "OLLAMA_TIMEOUT",
                "La generacion excedio el tiempo de espera.");
    }

    @ExceptionHandler(AiModelClient.ProviderTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleProviderTimeout(
            AiModelClient.ProviderTimeoutException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_PROVIDER_TIMEOUT", exception);
        return error(HttpStatus.GATEWAY_TIMEOUT,
                "AI_PROVIDER_TIMEOUT",
                "La generacion excedio el tiempo de espera.");
    }

    @ExceptionHandler(AiModelClient.ModelOutputTruncatedException.class)
    public ResponseEntity<Map<String, Object>> handleTruncatedModelOutput(
            AiModelClient.ModelOutputTruncatedException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_MODEL_OUTPUT_TRUNCATED", exception);
        return error(HttpStatus.BAD_GATEWAY,
                "MODEL_OUTPUT_TRUNCATED",
                "La respuesta del modelo quedó incompleta; no se generó un resultado parcial.");
    }

    @ExceptionHandler(AiModelClient.InvalidModelResponseException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidModelResponse(
            AiModelClient.InvalidModelResponseException exception) {
        LOGGER.log(Level.WARNING, "AI_SERVICE_INVALID_MODEL_OUTPUT: " + exception.getMessage(), exception);
        return error(HttpStatus.BAD_GATEWAY,
                "INVALID_MODEL_OUTPUT",
                "La respuesta del modelo no pudo validarse.");
    }

    @ExceptionHandler(DocumentExtractionService.NoUsableHierarchyException.class)
    public ResponseEntity<Map<String, Object>> handleNoUsableHierarchy(
            DocumentExtractionService.NoUsableHierarchyException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_NO_USABLE_HIERARCHY");
        return error(HttpStatus.UNPROCESSABLE_CONTENT,
                "NO_USABLE_HIERARCHY",
                exception.getMessage());
    }

    @ExceptionHandler(PdfTextExtractor.InvalidPdfException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPdf(PdfTextExtractor.InvalidPdfException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_PDF_INVALID", exception);
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "PDF_INVALID", exception.getMessage());
    }

    @ExceptionHandler(PdfTextExtractor.NoExtractableTextException.class)
    public ResponseEntity<Map<String, Object>> handleNoExtractableText(
            PdfTextExtractor.NoExtractableTextException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_NO_EXTRACTABLE_TEXT");
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "NO_EXTRACTABLE_TEXT", exception.getMessage());
    }

    @ExceptionHandler(PdfTextExtractor.PdfTextTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleExtractedTextTooLarge(
            PdfTextExtractor.PdfTextTooLargeException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_EXTRACTED_TEXT_TOO_LARGE");
        return error(HttpStatusCode.valueOf(413), "EXTRACTED_TEXT_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(PdfTextExtractor.PdfPageLimitException.class)
    public ResponseEntity<Map<String, Object>> handlePdfPageLimit(
            PdfTextExtractor.PdfPageLimitException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_PDF_PAGE_LIMIT");
        return error(HttpStatusCode.valueOf(413),
                "PDF_PAGE_LIMIT_EXCEEDED",
                exception.getMessage());
    }

    @ExceptionHandler(DocumentTextExtractor.InvalidDocumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidDocument(
            DocumentTextExtractor.InvalidDocumentException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_DOCUMENT_INVALID", exception);
        return error(HttpStatus.UNPROCESSABLE_CONTENT,
                "DOCUMENT_INVALID",
                exception.getMessage());
    }

    @ExceptionHandler(DocumentTextExtractor.NoExtractableTextException.class)
    public ResponseEntity<Map<String, Object>> handleNoDocumentText(
            DocumentTextExtractor.NoExtractableTextException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_NO_EXTRACTABLE_DOCUMENT_TEXT");
        return error(HttpStatus.UNPROCESSABLE_CONTENT,
                "NO_EXTRACTABLE_TEXT",
                exception.getMessage());
    }

    @ExceptionHandler(DocumentTextExtractor.DocumentTextTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentTextTooLarge(
            DocumentTextExtractor.DocumentTextTooLargeException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_DOCUMENT_TEXT_TOO_LARGE");
        return error(HttpStatusCode.valueOf(413),
                "EXTRACTED_TEXT_TOO_LARGE",
                exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> handleInvalidRequest() {
        LOGGER.log(Level.INFO, "AI_SERVICE_INVALID_REQUEST");
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "La solicitud no es valida.");
    }

    @ExceptionHandler(DocumentExtractionService.IncompleteHierarchyException.class)
    public ResponseEntity<Map<String, Object>> handleIncompleteHierarchy(
            DocumentExtractionService.IncompleteHierarchyException exception) {
        LOGGER.log(Level.INFO,
                "AI_SERVICE_INCOMPLETE_HIERARCHY: " + String.join(" | ", exception.getValidationErrors()));
        return errorWithValidation(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INCOMPLETE_HIERARCHY",
                "La jerarquia extraida esta incompleta.",
                exception.getValidationErrors());
    }

    @ExceptionHandler(CurriculumGenerationService.InvalidCurriculumTreeException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCurriculumTree(
            CurriculumGenerationService.InvalidCurriculumTreeException exception) {
        LOGGER.log(Level.INFO,
                "AI_SERVICE_INVALID_CURRICULUM_TREE: " + exception.getMessage());
        if (exception.getValidationErrors().isEmpty()) {
            return error(HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_CURRICULUM_TREE",
                    exception.getMessage());
        }
        return errorWithValidation(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_CURRICULUM_TREE",
                exception.getMessage(),
                exception.getValidationErrors());
    }

    @ExceptionHandler(CurriculumGenerationService.InvalidCurriculumOutputException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCurriculumOutput(
            CurriculumGenerationService.InvalidCurriculumOutputException exception) {
        LOGGER.log(Level.WARNING,
                "AI_SERVICE_INVALID_CURRICULUM_OUTPUT: " + exception.getMessage());
        return errorWithValidation(
                HttpStatus.BAD_GATEWAY,
                "INVALID_CURRICULUM_OUTPUT",
                exception.getMessage(),
                exception.getValidationErrors());
    }

    @ExceptionHandler(CurriculumGenerationService.NoRelevantCurriculumException.class)
    public ResponseEntity<Map<String, Object>> handleNoRelevantCurriculum(
            CurriculumGenerationService.NoRelevantCurriculumException exception) {
        LOGGER.log(Level.INFO, "AI_SERVICE_NO_RELEVANT_CURRICULUM");
        return error(HttpStatus.UNPROCESSABLE_CONTENT,
                "SIN_INFORMACION_RELEVANTE",
                exception.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatusCode status,
            String code,
            String message) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message));
    }

    private static ResponseEntity<Map<String, Object>> errorWithValidation(
            HttpStatusCode status,
            String code,
            String message,
            List<String> validationErrors) {
        return ResponseEntity.status(status).body(Map.of(
                "code", code,
                "message", message,
                "validationErrors", validationErrors));
    }
}
