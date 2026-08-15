package com.grilld.backend.user;

import com.grilld.backend.common.exception.ResourceNotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The Phase 1 "protected endpoint" - proves the whole auth chain works end to
 * end: no token (or an invalid one) -> 401, rejected before this method even
 * runs, by the security filter chain (see SecurityConfig). A valid token ->
 * this method runs and returns the logged-in user's own data.
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final UserRepository userRepository;

    public MeController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("No user for token subject " + userId));
        return UserResponse.from(user);
    }
}
