package com.grilld.backend.common.exception;

/**
 * Thrown when speech-to-text isn't wired up yet ({@code grilld.voice.provider=none},
 * the default) or the configured provider itself fails. Same "fail honestly, don't
 * fake it" pattern as {@link AiServiceUnavailableException} - surfaced to the user
 * as a clear message to type their answer instead, never a silent no-op.
 */
public class TranscriptionUnavailableException extends RuntimeException {
    public TranscriptionUnavailableException(String message) {
        super(message);
    }

    public TranscriptionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
