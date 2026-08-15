package com.grilld.backend.auth;

import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Runs once, right after Google confirms "this really is the account owner."
 * From here on the OAuth2 handshake is done and irrelevant - we look the
 * person up (or create them) in our own `users` table and hand back our own
 * JWT, which is what every subsequent API call actually authenticates with.
 *
 * Returns the token as plain JSON for now rather than redirecting to a
 * frontend callback URL, since the frontend doesn't exist yet (Phase 9).
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final TokenService tokenService;

    public OAuth2LoginSuccessHandler(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String googleId = oAuth2User.getName(); // the "sub" claim - Google's stable unique ID for this account
        String email = oAuth2User.getAttribute("email");

        User user = userService.findOrCreateFromGoogle(googleId, email);
        String token = tokenService.issueFor(user);

        // A JWT is only base64url characters and dots - never quotes/backslashes -
        // so it's always safe to embed directly without a JSON library.
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"token\":\"" + token + "\"}");
    }
}
