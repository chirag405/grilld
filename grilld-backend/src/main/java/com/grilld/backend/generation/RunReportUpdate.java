package com.grilld.backend.generation;

/** One SSE frame / poll response for a run's Run Report (§10.3). */
public record RunReportUpdate(String status, String runReportMd, String failureReason) {
}
