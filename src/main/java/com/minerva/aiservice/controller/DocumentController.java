package com.minerva.aiservice.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.minerva.aiservice.dto.DocumentExtractionResponse;
import com.minerva.aiservice.service.DocumentExtractionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentExtractionService documentExtractionService;

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentExtractionResponse> extract(@RequestPart("file") MultipartFile file) {
        return ResponseEntity.ok(documentExtractionService.extract(file));
    }
}
