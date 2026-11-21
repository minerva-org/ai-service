package com.minerva.aiservice.controller;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.DocumentLabRequest;
import com.minerva.aiservice.dto.DocumentLabResponse;
import com.minerva.aiservice.service.DocumentLaboratoryService;
import com.minerva.aiservice.service.DocumentLaboratoryService.PromptDefaults;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/laboratory/documents")
@RequiredArgsConstructor
public class DocumentLaboratoryController {

    private static final Logger LOGGER =
            Logger.getLogger(DocumentLaboratoryController.class.getName());
    private final DocumentLaboratoryService service;

    @GetMapping("/prompts")
    public PromptDefaults prompts() {
        return service.defaults();
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentLabResponse> extract(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("request") DocumentLabRequest request) {
        long startedAt = System.nanoTime();
        LOGGER.log(Level.INFO,
                "PETICION_RECIBIDA: operacion=LABORATORIO_EXTRACCION, archivo={0}, bytes={1}, sourceType={2}",
                new Object[]{file.getOriginalFilename(), file.getSize(), request.sourceType()});
        try {
            DocumentLabResponse result = service.extract(file, request);
            HttpStatus status = result.isValid()
                    ? HttpStatus.OK
                    : HttpStatus.UNPROCESSABLE_CONTENT;
            LOGGER.log(Level.INFO,
                    "PETICION_COMPLETADA: operacion=LABORATORIO_EXTRACCION, archivo={0}, status={1}, duracionMs={2}",
                    new Object[]{file.getOriginalFilename(), status.value(),
                            (System.nanoTime() - startedAt) / 1_000_000});
            return ResponseEntity.status(status).body(result);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING,
                    "PETICION_FINALIZADA_CON_ERROR: operacion=LABORATORIO_EXTRACCION, archivo={0}, error={1}",
                    new Object[]{file.getOriginalFilename(), exception.getClass().getSimpleName()});
            throw exception;
        }
    }
}
