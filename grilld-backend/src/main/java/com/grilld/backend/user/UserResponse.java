package com.grilld.backend.user;

import java.time.Instant;
import java.util.UUID;

/**
 * A DTO (Data Transfer Object) - what actually goes out over the API. Kept
 * separate from the User entity on purpose: entity fields can change for
 * internal/database reasons without silently changing the public API shape,
 * and we control exactly what's exposed (nothing here reveals googleId, for
 * instance).
 */
public record UserResponse(
        UUID id,
        String email,
        User.Plan plan,
        int creditsBalance,
        Instant createdAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getPlan(),
                user.getCreditsBalance(),
                user.getCreatedAt()
        );
    }
}
