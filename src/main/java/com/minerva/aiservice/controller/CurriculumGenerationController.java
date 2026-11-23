package com.minerva.aiservice.controller;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.CurriculumGenerationRequest;
import com.minerva.aiservice.dto.CurriculumGenerationResponse;
import com.minerva.aiservice.service.CurriculumGenerationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/curriculum")
@RequiredArgsConstructor
public class CurriculumGenerationController {

    private static final Logger LOGGER =
            Logger.getLogger(CurriculumGenerationController.class.getName());

    private final CurriculumGenerationService service;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CurriculumGenerationResponse> generate(
            @Valid @RequestPart("request") CurriculumGenerationRequest request,
            @RequestPart(value = "files", required = false) MultipartFile[] files) {
        long startedAt = System.nanoTime();
        LOGGER.log(Level.INFO,
                "PETICION_RECIBIDA: operacion=CURRICULUM_GENERATE, grupo={0}, fuentesNuevas={1}",
                new Object[]{
                        request.grupoId(),
                        request.fuentes().size()});
        try {
            CurriculumGenerationResponse response =
                    service.generate(request, files);
            LOGGER.log(Level.INFO,
                    "PETICION_COMPLETADA: operacion=CURRICULUM_GENERATE, grupo={0}, duracionMs={1}",
                    new Object[]{
                            request.grupoId(),
                            (System.nanoTime() - startedAt) / 1_000_000});
            return ResponseEntity.ok(response);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING,
                    "PETICION_FINALIZADA_CON_ERROR: operacion=CURRICULUM_GENERATE, grupo={0}, error={1}",
                    new Object[]{
                            request.grupoId(),
                            exception.getClass().getSimpleName()});
            throw exception;
        }
    }
}
