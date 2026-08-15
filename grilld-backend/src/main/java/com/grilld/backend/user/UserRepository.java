package com.grilld.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * A "repository" interface - Spring Data JPA generates the implementation at
 * runtime. Extending JpaRepository already gives us save(), findById(), etc.
 * for free; we only need to declare the extra lookup methods below, and Spring
 * derives the query from the method name itself (no SQL written here).
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByGoogleId(String googleId);
}
