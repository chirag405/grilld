package com.grilld.backend.voice;

import com.grilld.backend.common.exception.TranscriptionUnavailableException;

/**
 * Turns a recorded answer into text. One interface, swappable implementation -
 * same seam pattern as {@code AiServiceClient} and {@code PackageStorage}:
 * nothing above this layer (TranscriptionController, AnswerForm on the
 * frontend) needs to change once a real provider is chosen and
 * {@link UnconfiguredTranscriptionService} is replaced with one that actually
 * calls it (e.g. ElevenLabs Scribe, Fish Speech, Whisper) - see
 * docs/phases/phase-12/SETUP.md.
 */
public interface TranscriptionService {

    /**
     * @param audioBytes  the raw recorded clip, exactly as the browser produced it
     * @param contentType its MIME type (e.g. {@code audio/webm}), passed straight
     *                    through from the multipart upload for a provider that
     *                    needs it
     * @throws TranscriptionUnavailableException if no provider is configured, or
     *                                            the configured provider call fails
     */
    String transcribe(byte[] audioBytes, String contentType);
}
