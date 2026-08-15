package com.grilld.backend.session;

import jakarta.validation.constraints.NotBlank;

public record SubmitAnswerRequest(@NotBlank String answerText) {
}
