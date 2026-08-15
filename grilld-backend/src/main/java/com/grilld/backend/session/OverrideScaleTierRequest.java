package com.grilld.backend.session;

import jakarta.validation.constraints.Pattern;

public record OverrideScaleTierRequest(@Pattern(regexp = "T[0-3]") String tier) {
}
