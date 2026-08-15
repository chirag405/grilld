package com.grilld.backend.common.exception;

import java.time.Instant;

/**
 * Consistent error body for every API error response, regardless of which
 * exception produced it. Callers (the frontend, and later the Python AI
 * service for its Spring-facing calls) should only ever need to parse one
 * error shape.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path);
    }
}
