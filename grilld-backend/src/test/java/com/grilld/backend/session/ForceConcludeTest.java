package com.grilld.backend.session;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Proves the escape hatch (product-and-architecture.md §7: "user can
 * force-accept after N rounds") actually transitions the session, regardless
 * of how far the interview got - no rubric check, unconditional.
 */
@Testcontainers
@SpringBootTest
class ForceConcludeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    DiscoverySessionRepository sessionRepository;

    @Autowired
    UserService userService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    @Test
    void forceConcludeTransitionsAnActiveSessionRegardlessOfInterviewState() {
        User user = userService.findOrCreateFromGoogle("force-conclude-google-id", "force-conclude@example.com", null, null);
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        DiscoverySession beforeForceConclude = sessionRepository.findById(started.sessionId()).orElseThrow();
        assertEquals(DiscoverySession.Status.ACTIVE, beforeForceConclude.getStatus(),
                "sanity check - the interview has barely started, still active");

        sessionService.forceConclude(started.sessionId());

        DiscoverySession afterForceConclude = sessionRepository.findById(started.sessionId()).orElseThrow();
        assertEquals(DiscoverySession.Status.READY_FOR_GENERATION, afterForceConclude.getStatus());
    }
}
