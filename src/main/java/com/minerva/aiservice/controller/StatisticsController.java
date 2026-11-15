package com.minerva.aiservice.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.minerva.aiservice.dto.StatisticsInterpretationRequest;
import com.minerva.aiservice.dto.StatisticsInterpretationResponse;
import com.minerva.aiservice.service.StatisticsInterpretationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsInterpretationService statisticsInterpretationService;

    @PostMapping("/interpret")
    public StatisticsInterpretationResponse interpret(
            @Valid @RequestBody StatisticsInterpretationRequest request) {
        return statisticsInterpretationService.interpret(request);
    }
}
