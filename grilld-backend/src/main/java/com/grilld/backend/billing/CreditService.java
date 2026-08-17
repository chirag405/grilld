package com.grilld.backend.billing;

import com.grilld.backend.common.exception.InsufficientCreditsException;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import com.grilld.backend.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The sole writer of `users.credits_balance` (product-and-architecture.md
 * §9's rule: "never mutate balance without a row" in credit_transactions).
 * Every method here is one atomic balance change plus exactly one audit row,
 * in the same transaction - a caller can never see a balance move without a
 * matching, queryable reason.
 * <p>
 * Deduction is race-free by construction: {@link UserRepository#deductCredits}
 * is a single conditional UPDATE ("subtract only if the balance still covers
 * it"), not a read-then-write - two concurrent runs racing to spend a user's
 * last 50 credits can't both succeed.
 */
@Service
public class CreditService {

    private final UserRepository userRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    public CreditService(UserRepository userRepository, CreditTransactionRepository creditTransactionRepository) {
        this.userRepository = userRepository;
        this.creditTransactionRepository = creditTransactionRepository;
    }

    /**
     * Pre-authorization deduction for a generation run
     * (decisions-and-technical-architecture.md §13 risk mitigation table:
     * "credit pre-authorization before run starts"). Throws rather than
     * partially proceeding - the caller (GenerationService) must not create
     * or keep a run when this fails.
     */
    @Transactional
    public void deductForRun(UUID userId, int amount, UUID runId, String reason) {
        int updated = userRepository.deductCredits(userId, amount);
        if (updated == 0) {
            throw new InsufficientCreditsException(
                    "Not enough credits for user " + userId + " (needs " + amount + ")");
        }
        creditTransactionRepository.save(new CreditTransaction(userId, -amount, reason, runId));
    }

    /** Gives back a run's charge after it fails - a run that never delivered a package shouldn't cost credits. */
    @Transactional
    public void refundForRun(UUID userId, int amount, UUID runId, String reason) {
        int updated = userRepository.addCredits(userId, amount);
        if (updated == 0) {
            throw new ResourceNotFoundException("No user " + userId + " to refund");
        }
        creditTransactionRepository.save(new CreditTransaction(userId, amount, reason, runId));
    }

    /**
     * Grants credits (free signup top-ups, Lemon Squeezy purchases) exactly
     * once per {@code idempotencyReason} - a webhook redelivered after a slow
     * 200 (or retried after a transient non-200) must not double-credit the
     * account. The reason string itself is the dedupe key, so callers must
     * pass something globally unique to the triggering event (e.g. the Lemon
     * Squeezy order id), never a generic label.
     *
     * @return true if credits were actually granted, false if this reason was already recorded
     */
    @Transactional
    public boolean grantIdempotent(UUID userId, int amount, String idempotencyReason) {
        if (creditTransactionRepository.existsByReason(idempotencyReason)) {
            return false;
        }
        int updated = userRepository.addCredits(userId, amount);
        if (updated == 0) {
            throw new ResourceNotFoundException("No user " + userId + " to grant credits to");
        }
        creditTransactionRepository.save(new CreditTransaction(userId, amount, idempotencyReason, null));
        return true;
    }
}
