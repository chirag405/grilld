package com.grilld.backend.voice;

import com.grilld.backend.common.exception.TranscriptionUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default {@link TranscriptionService} - active whenever
 * {@code grilld.voice.provider} is unset or {@code none} (i.e. every
 * deployment today; no provider has been chosen yet). Fails loudly and
 * honestly rather than pretending to transcribe anything, matching the
 * project's "surface real errors, never fabricate a result" rule - the
 * frontend's VoiceRecorder shows this message and falls back to typing.
 */
@Component
@ConditionalOnProperty(prefix = "grilld.voice", name = "provider", havingValue = "none", matchIfMissing = true)
public class UnconfiguredTranscriptionService implements TranscriptionService {

    @Override
    public String transcribe(byte[] audioBytes, String contentType) {
        throw new TranscriptionUnavailableException(
                "Voice input isn't turned on yet on this deployment - type your answer instead.");
    }
}
