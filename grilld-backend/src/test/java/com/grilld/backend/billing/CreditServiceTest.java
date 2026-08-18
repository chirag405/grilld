package com.grilld.backend.billing;

import com.grilld.backend.aiservice.AiServiceClient;
import com.grilld.backend.aiservice.InterrogatorTurnResult;
import com.grilld.backend.aiservice.ScaleCalibrationResult;
import com.grilld.backend.brief.ProjectBrief;
import com.grilld.backend.brief.ProjectBriefRepository;
import com.grilld.backend.common.exception.InsufficientCreditsException;
import com.grilld.backend.generation.GenerationRun;
import com.grilld.backend.generation.GenerationRunRepository;
import com.grilld.backend.session.SessionService;
import com.grilld.backend.user.User;
import com.grilld.backend.user.UserRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Proves CreditService's two guarantees directly, against a real Postgres
 * balance column - not just that GenerationService happens to call it
 * correctly (that's GenerationServiceTest's job): every balance change has
 * exactly one matching credit_transactions row, and a deduction that would
 * overdraw the balance is refused rather than allowed to go negative.
 */
@Testcontainers
@SpringBootTest
class CreditServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    SessionService sessionService;

    @Autowired
    ProjectBriefRepository briefRepository;

    @Autowired
    GenerationRunRepository generationRunRepository;

    @Autowired
    UserService userService;

    @Autowired
    UserRepository userRepository;

    @Autowired
    CreditTransactionRepository creditTransactionRepository;

    @Autowired
    CreditService creditService;

    @MockitoBean
    AiServiceClient aiServiceClient;

    /** No free signup grant any more - tests that need a spendable balance top up explicitly. */
    private User freshUser(String googleId, String email) {
        User user = userService.findOrCreateFromGoogle(googleId, email, null, null);
        creditService.grantIdempotent(user.getId(), 60, "test-setup-grant:" + googleId);
        return user;
    }

    /** credit_transactions.run_id is a real FK to generation_runs - these tests need an actual row, not a random UUID. */
    private UUID freshRunId(User user) {
        InterrogatorTurnResult.NextQuestion question = new InterrogatorTurnResult.NextQuestion(
                "What's the core problem?", List.of("problem_statement"), "FREE_ELICITATION", "text", "opening");
        when(aiServiceClient.nextTurn(ArgumentMatchers.any())).thenReturn(
                new InterrogatorTurnResult(List.of(), List.of(), List.of(), question, false));
        SessionService.SessionStartResult started = sessionService.startSession(user.getId(), "a scheduling tool");

        when(aiServiceClient.calibrateScale(ArgumentMatchers.any())).thenReturn(
                new ScaleCalibrationResult("T1", "solo builder", List.of("solo")));
        sessionService.calibrateScale(started.sessionId());

        ProjectBrief brief = briefRepository.findBySessionId(started.sessionId()).orElseThrow();
        GenerationRun run = generationRunRepository.save(new GenerationRun(brief.getId()));
        return run.getId();
    }

    @Test
    void deductForRunLowersBalanceAndRecordsANegativeAuditRow() {
        User user = freshUser("credit-deduct-google-id", "credit-deduct@example.com");
        UUID runId = freshRunId(user);

        creditService.deductForRun(user.getId(), 50, runId, "GENERATION_RUN:" + runId);

        assertEquals(10, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance());
        List<CreditTransaction> transactions = creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
        assertEquals(2, transactions.size(), "the test-setup grant plus this deduction");
        assertEquals(-50, transactions.get(0).getDelta());
        assertEquals(runId, transactions.get(0).getRunId());
    }

    @Test
    void deductForRunRefusesToOverdraw() {
        User user = freshUser("credit-overdraw-google-id", "credit-overdraw@example.com");

        assertThrows(InsufficientCreditsException.class,
                () -> creditService.deductForRun(user.getId(), 61, null, "too much"));

        assertEquals(60, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance(),
                "a refused deduction must not touch the balance");
        assertEquals(1, creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).size(),
                "a refused deduction must not leave an audit row either - only the test-setup grant should exist");
    }

    @Test
    void refundForRunAddsBackAndRecordsAPositiveAuditRow() {
        User user = freshUser("credit-refund-google-id", "credit-refund@example.com");
        UUID runId = freshRunId(user);
        creditService.deductForRun(user.getId(), 50, runId, "GENERATION_RUN:" + runId);

        creditService.refundForRun(user.getId(), 50, runId, "GENERATION_RUN_REFUND:" + runId);

        assertEquals(60, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance());
        List<Integer> deltas = creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().map(CreditTransaction::getDelta).toList();
        assertTrue(deltas.contains(-50) && deltas.contains(50));
    }

    @Test
    void grantIdempotentGrantsOnceAndNoOpsOnAReplayedReason() {
        User user = freshUser("credit-grant-google-id", "credit-grant@example.com");
        String reason = "LEMON_SQUEEZY_ORDER:order-123";

        boolean firstGrant = creditService.grantIdempotent(user.getId(), 50, reason);
        boolean secondGrant = creditService.grantIdempotent(user.getId(), 50, reason);

        assertTrue(firstGrant);
        assertFalse(secondGrant, "a redelivered webhook must not double-credit the account");
        assertEquals(110, userRepository.findById(user.getId()).orElseThrow().getCreditsBalance(),
                "60 test-setup grant + 50 from exactly one purchase grant");
        assertEquals(1, creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream().filter(t -> t.getReason().equals(reason)).count());
    }
}
