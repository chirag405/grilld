package com.grilld.backend.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "@Service" marks this as a bean holding business logic (as opposed to
 * @Repository for data access or @RestController for handling HTTP directly).
 * Spring creates one instance of this and hands it to anything that asks for
 * it (dependency injection) - see OAuth2LoginSuccessHandler and MeController.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * First login from a given Google account creates the user (and grants the
     * free credit signup bonus, via User's default). Every login after that
     * just looks the existing row up. This is the anti-abuse gate from
     * docs/product-and-architecture.md §10 - the free grant is only ever
     * reachable through a real Google account, never a bare signup form.
     */
    @Transactional
    public User findOrCreateFromGoogle(String googleId, String email) {
        return userRepository.findByGoogleId(googleId)
                .orElseGet(() -> userRepository.save(new User(email, googleId)));
    }
}
