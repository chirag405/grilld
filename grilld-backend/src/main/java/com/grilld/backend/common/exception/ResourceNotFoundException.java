package com.grilld.backend.common.exception;

/** Thrown when a requested entity (session, brief, run, ...) doesn't exist or isn't visible to the caller. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
