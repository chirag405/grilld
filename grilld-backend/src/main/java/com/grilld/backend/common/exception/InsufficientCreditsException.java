package com.grilld.backend.common.exception;

/** Thrown when a user's credits_balance can't cover a requested deduction - blocked, not queued or partially run. */
public class InsufficientCreditsException extends RuntimeException {
    public InsufficientCreditsException(String message) {
        super(message);
    }
}
