package com.grilld.backend.billing;

import com.grilld.backend.user.User;
import com.grilld.backend.user.UserRepository;
import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The authenticated user's own view of their credits: current balance, the
 * audit trail behind it, and a checkout link for buying more
 * (product-and-architecture.md §10, MVP scope item 7). Nothing here mutates
 * a balance - that only ever happens in CreditService, driven by a real
 * generation run or a verified Lemon Squeezy webhook.
 */
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final UserRepository userRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final LemonSqueezyCheckoutService checkoutService;

    public BillingController(UserRepository userRepository, CreditTransactionRepository creditTransactionRepository,
                              LemonSqueezyCheckoutService checkoutService) {
        this.userRepository = userRepository;
        this.creditTransactionRepository = creditTransactionRepository;
        this.checkoutService = checkoutService;
    }

    @GetMapping("/balance")
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
        User user = requireUser(jwt);
        List<TransactionView> recent = creditTransactionRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(t -> new TransactionView(t.getDelta(), t.getReason(), t.getCreatedAt()))
                .toList();
        return new BalanceResponse(user.getCreditsBalance(), recent);
    }

    @GetMapping("/checkout-url")
    public CheckoutUrlResponse checkoutUrl(@AuthenticationPrincipal Jwt jwt, @RequestParam CreditPackage creditPackage) {
        User user = requireUser(jwt);
        return new CheckoutUrlResponse(checkoutService.buildCheckoutUrl(user, creditPackage));
    }

    private User requireUser(Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No user " + userId));
    }

    public record BalanceResponse(int creditsBalance, List<TransactionView> recentTransactions) {
    }

    public record TransactionView(int delta, String reason, Instant createdAt) {
    }

    public record CheckoutUrlResponse(String checkoutUrl) {
    }
}
