package com.grilld.backend.voice;

import com.grilld.backend.common.exception.TranscriptionUnavailableException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Turns a recorded answer clip into text for AnswerForm's voice_primary mode
 * (interrogation-engine.md's "hybrid input, decided per question"). Not
 * scoped to a session - transcription doesn't read or write any session
 * state, the caller just needs to be an authenticated user (SecurityConfig's
 * default) - so there's nothing to check ownership of beyond that. Rate
 * limited under the same "interview" tier as answering a question
 * (RateLimitConfig), since it's the same per-turn action.
 */
@RestController
@RequestMapping("/api/v1/voice")
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    public TranscriptionController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @PostMapping(path = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResult transcribe(@RequestParam("audio") MultipartFile audio) {
        if (audio.isEmpty()) {
            throw new TranscriptionUnavailableException("No audio was received - try recording again.");
        }
        try {
            String text = transcriptionService.transcribe(audio.getBytes(), audio.getContentType());
            return new TranscriptionResult(text);
        } catch (IOException e) {
            throw new TranscriptionUnavailableException("Couldn't read the recorded audio - try again.", e);
        }
    }

    public record TranscriptionResult(String text) {
    }
}
