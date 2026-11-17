package com.minerva.aiservice.controller;

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

    private final DocumentLaboratoryService service;

    @GetMapping("/prompts")
    public PromptDefaults prompts() {
        return service.defaults();
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentLabResponse> extract(
            @RequestPart("file") MultipartFile file,
            @Valid @RequestPart("request") DocumentLabRequest request) {
        DocumentLabResponse result = service.extract(file, request);
        return ResponseEntity.status(result.isValid() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_CONTENT)
                .body(result);
    }
}
