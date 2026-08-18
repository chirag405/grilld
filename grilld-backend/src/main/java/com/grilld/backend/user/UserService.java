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
     * First login from a given Google account creates the user (0 credits -
     * every credit is purchased, see CreditService). Every login after that
     * just looks the existing row up and refreshes their Google profile info.
     */
    @Transactional
    public User findOrCreateFromGoogle(String googleId, String email, String name, String pictureUrl) {
        User user = userRepository.findByGoogleId(googleId)
                .orElseGet(() -> new User(email, googleId));
        user.updateProfile(name, pictureUrl);
        return userRepository.save(user);
    }
}
