package com.grilld.backend.voice;

import com.grilld.backend.common.exception.TranscriptionUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A plain unit test, not a Testcontainers/Spring-context one - the whole
 * point of the {@link TranscriptionService} seam is that the controller
 * doesn't need a database or a real provider to be exercised in isolation
 * (see {@link UnconfiguredTranscriptionService}'s own Javadoc). Proves the
 * "no provider configured" path fails honestly rather than silently.
 */
class TranscriptionControllerTest {

    private final TranscriptionController controller = new TranscriptionController(new UnconfiguredTranscriptionService());

    @Test
    void unconfiguredProviderFailsHonestlyRatherThanFakingATranscript() {
        MockMultipartFile audio = new MockMultipartFile("audio", "clip.webm", "audio/webm", "fake-bytes".getBytes());

        TranscriptionUnavailableException ex = assertThrows(TranscriptionUnavailableException.class,
                () -> controller.transcribe(audio));
        assertTrue(ex.getMessage().toLowerCase().contains("voice input"),
                "expected an honest not-turned-on message, got: " + ex.getMessage());
    }

    @Test
    void emptyUploadFailsBeforeEverCallingTheProvider() {
        MockMultipartFile empty = new MockMultipartFile("audio", "clip.webm", "audio/webm", new byte[0]);

        assertThrows(TranscriptionUnavailableException.class, () -> controller.transcribe(empty));
    }
}
