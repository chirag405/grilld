package com.grilld.backend.auth;

import com.grilld.backend.user.User;
import com.grilld.backend.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A plain Mockito unit test (no Spring context, no database) - deliberately
 * isolated from real UserService/TokenService so it exercises exactly what
 * this class is responsible for: extracting the right fields from Google's
 * OAuth2User and redirecting to the frontend's callback route with the
 * token attached. This does NOT test the OAuth2 redirect handshake itself
 * (that needs real Google credentials - see docs/phases/phase-1/TESTING.md's
 * manual checklist), only the code that runs after Spring Security has
 * already confirmed the login succeeded.
 */
class OAuth2LoginSuccessHandlerTest {

    @Test
    void extractsGoogleIdAndEmailAndRedirectsToTheFrontendWithTheToken() throws Exception {
        UserService userService = mock(UserService.class);
        TokenService tokenService = mock(TokenService.class);
        OAuth2LoginSuccessHandler handler =
                new OAuth2LoginSuccessHandler(userService, tokenService, "http://localhost:3000");

        User user = new User("person@example.com", "google-sub-123");
        when(userService.findOrCreateFromGoogle("google-sub-123", "person@example.com", "Person Example", "https://example.com/pic.jpg"))
                .thenReturn(user);
        when(tokenService.issueFor(user)).thenReturn("fake.jwt.token");

        OAuth2User oAuth2User = new DefaultOAuth2User(
                java.util.List.of(() -> "ROLE_USER"),
                Map.of("sub", "google-sub-123", "email", "person@example.com",
                        "name", "Person Example", "picture", "https://example.com/pic.jpg"),
                "sub");
        Authentication authentication = new TestingAuthenticationToken(oAuth2User, null);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(userService).findOrCreateFromGoogle(eq("google-sub-123"), eq("person@example.com"),
                eq("Person Example"), eq("https://example.com/pic.jpg"));
        verify(tokenService).issueFor(user);

        assertEquals("http://localhost:3000/auth/callback?token=fake.jwt.token", response.getRedirectedUrl());
    }
}
