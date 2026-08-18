package com.grilld.backend.common.exception;

/** Thrown when a call to grilld-ai-service (or the LLM provider behind it) fails - network error,
 * non-2xx from the LangGraph Platform, or a provider-side failure like an exhausted API key/quota. */
public class AiServiceUnavailableException extends RuntimeException {
    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
