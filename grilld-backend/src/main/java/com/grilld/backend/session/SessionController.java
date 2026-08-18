package com.grilld.backend.session;

import com.grilld.backend.aiservice.ScaleCalibrationResult;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The first real product endpoints: start an interrogation, answer its
 * questions one at a time. Exercises the full Phase 2 pipeline end to end -
 * WorkingContextAssembler -> AiServiceClient (the stub, until Phase 3) ->
 * persistence - even though the AI side is canned for now.
 * <p>
 * Every {@code sessionId}-scoped endpoint below calls
 * {@link SessionService#verifyOwnership} first (Phase 8 hardening) - before
 * that, any authenticated user could read or mutate any other user's
 * session just by knowing its id.
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

    @GetMapping
    public List<SessionService.SessionSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return sessionService.listSessions(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{sessionId}")
    public SessionService.SessionDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        return sessionService.getSessionDetail(sessionId, UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/{sessionId}/turns")
    public List<SessionService.TurnHistoryEntry> turns(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        sessionService.verifyOwnership(sessionId, UUID.fromString(jwt.getSubject()));
        return sessionService.getTurnHistory(sessionId);
    }

    @PostMapping("/{sessionId}/answer")
    public SessionService.TurnAnswerResult answer(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                                    @Valid @RequestBody SubmitAnswerRequest request) {
        sessionService.verifyOwnership(sessionId, UUID.fromString(jwt.getSubject()));
        return sessionService.submitAnswer(sessionId, request.answerText());
    }

    @PostMapping("/{sessionId}/scale-tier")
    public ScaleCalibrationResult calibrateScale(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        sessionService.verifyOwnership(sessionId, UUID.fromString(jwt.getSubject()));
        return sessionService.calibrateScale(sessionId);
    }

    @PostMapping("/{sessionId}/force-conclude")
    public void forceConclude(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId) {
        sessionService.verifyOwnership(sessionId, UUID.fromString(jwt.getSubject()));
        sessionService.forceConclude(sessionId);
    }

    @PutMapping("/{sessionId}/slots/{slotKey}")
    public void editSlot(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                         @PathVariable String slotKey, @Valid @RequestBody EditSlotRequest request) {
        sessionService.verifyOwnership(sessionId, UUID.fromString(jwt.getSubject()));
        sessionService.editSlot(sessionId, slotKey, request.value());
    }

    public record EditSlotRequest(@jakarta.validation.constraints.NotBlank String value) {
    }

    @PutMapping("/{sessionId}/scale-tier")
    public void overrideScaleTier(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID sessionId,
                                   @Valid @RequestBody OverrideScaleTierRequest request) {
        sessionService.verifyOwnership(sessionId, UUID.fromString(jwt.getSubject()));
        sessionService.overrideScaleTier(sessionId, request.tier());
    }
}
