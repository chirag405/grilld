package com.grilld.backend.session;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The first real product endpoints: start an interrogation, answer its
 * questions one at a time. Exercises the full Phase 2 pipeline end to end -
 * WorkingContextAssembler -> AiServiceClient (the stub, until Phase 3) ->
 * persistence - even though the AI side is canned for now.
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public SessionService.SessionStartResult start(@AuthenticationPrincipal Jwt jwt,
                                                     @Valid @RequestBody StartSessionRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return sessionService.startSession(userId, request.rawIdea());
    }

    @PostMapping("/{sessionId}/answer")
    public SessionService.TurnAnswerResult answer(@PathVariable UUID sessionId,
                                                    @Valid @RequestBody SubmitAnswerRequest request) {
        return sessionService.submitAnswer(sessionId, request.answerText());
    }
}
