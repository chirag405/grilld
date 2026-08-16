package com.grilld.backend.common.exception;

/** Thrown when the cost circuit breaker's kill switch is active (§10.6) - a new run is refused, not queued or degraded. */
public class GenerationBlockedException extends RuntimeException {
    public GenerationBlockedException(String message) {
        super(message);
    }
}
