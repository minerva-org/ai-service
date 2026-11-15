package com.minerva.aiservice.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minerva.aiservice.dto.QuizGenerationRequest;
import com.minerva.aiservice.dto.QuizGenerationResponse;
import com.minerva.aiservice.service.QuizGenerationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizGenerationService quizGenerationService;

    @PostMapping("/generate")
    public QuizGenerationResponse generate(@Valid @RequestBody QuizGenerationRequest request) {
        return quizGenerationService.generate(request);
    }
}
