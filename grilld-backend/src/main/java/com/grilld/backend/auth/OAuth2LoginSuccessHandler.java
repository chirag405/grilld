package com.grilld.backend.auth;

import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Runs once, right after Google confirms "this really is the account owner."
 * From here on the OAuth2 handshake is done and irrelevant - we look the
 * person up (or create them) in our own `users` table and hand back our own
 * JWT, which is what every subsequent API call actually authenticates with.
 * <p>
 * Redirects to the frontend's own callback route with the token as a query
 * parameter (Phase 9) - the frontend's Route Handler at that path is what
 * turns it into an httpOnly cookie; this class never sets a cookie itself,
 * since the frontend and backend are different origins in local dev and the
 * frontend is the one that needs to read the token to store it.
 */
@Component
public class OAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final UserService userService;
    private final TokenService tokenService;
    private final String frontendBaseUrl;

    public OAuth2LoginSuccessHandler(UserService userService, TokenService tokenService,
                                      @Value("${grilld.frontend.base-url}") String frontendBaseUrl) {
        this.userService = userService;
        this.tokenService = tokenService;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                         Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String googleId = oAuth2User.getName(); // the "sub" claim - Google's stable unique ID for this account
        String email = oAuth2User.getAttribute("email");
        String name = oAuth2User.getAttribute("name");
        String picture = oAuth2User.getAttribute("picture");

        User user = userService.findOrCreateFromGoogle(googleId, email, name, picture);
        String token = tokenService.issueFor(user);

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendBaseUrl)
                .path("/auth/callback")
                .queryParam("token", token)
                .build()
                .toUriString();
        response.sendRedirect(redirectUrl);
    }
}
