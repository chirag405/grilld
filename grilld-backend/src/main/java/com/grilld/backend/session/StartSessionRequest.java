package com.grilld.backend.session;

import jakarta.validation.constraints.NotBlank;

public record StartSessionRequest(@NotBlank String rawIdea) {
}
