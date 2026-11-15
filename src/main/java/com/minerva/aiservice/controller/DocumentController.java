package com.minerva.aiservice.controller;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.minerva.aiservice.config.AiServiceProperties;
import com.minerva.aiservice.dto.DocumentExtractionResponse;
import com.minerva.aiservice.service.DocumentExtractionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentExtractionService documentExtractionService;
    private final AiServiceProperties properties;

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentExtractionResponse> extract(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select a PDF file.");
        }
        if (file.getSize() > properties.limits().maxPdfBytes()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(413), "PDF exceeds the configured size limit.");
        }
        if (file.getOriginalFilename() == null
                || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only PDF files are accepted.");
        }

        try {
            return ResponseEntity.ok(documentExtractionService.extract(file.getBytes()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded PDF.");
        }
    }
}
