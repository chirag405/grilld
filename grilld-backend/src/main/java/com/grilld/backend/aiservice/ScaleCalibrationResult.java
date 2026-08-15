package com.grilld.backend.aiservice;

import java.util.List;

/**
 * Mirrors the Scale Calibrator's structured-output contract
 * (docs/product-and-architecture.md §4). The tier is a hard complexity
 * ceiling for every downstream specialist and is user-visible/overridable -
 * see ProjectBrief.applyScaleCalibration()/overrideScaleTier().
 */
public record ScaleCalibrationResult(String tier, String reasoning, List<String> signals) {
}
