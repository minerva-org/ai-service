package com.minerva.aiservice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record DocumentLabRequest(
        @NotNull SourceType sourceType,
        @NotNull @Valid ExtractionPrompts prompts) {

    public enum SourceType {
        STUDY_PLAN, SOURCE_MATERIAL
    }
}
