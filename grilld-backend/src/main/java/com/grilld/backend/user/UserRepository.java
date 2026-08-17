package com.grilld.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Atomic, race-free deduction: the WHERE clause's own balance check means
     * two concurrent deductions can't both read "60", both decide it's enough,
     * and both write - only one UPDATE can match and win under Postgres's
     * row-level locking for a single-statement conditional write. Returns 0
     * (not an exception) when the balance is insufficient, so CreditService
     * can tell "nothing happened" apart from "user doesn't exist" itself, and
     * decide what to throw - a bare repository shouldn't know about billing
     * exceptions.
     */
    @Modifying
    @Query("UPDATE User u SET u.creditsBalance = u.creditsBalance - :amount "
            + "WHERE u.id = :userId AND u.creditsBalance >= :amount")
    int deductCredits(@Param("userId") UUID userId, @Param("amount") int amount);

    /** Unconditional add - used for refunds and purchase grants, both always safe to apply. */
    @Modifying
    @Query("UPDATE User u SET u.creditsBalance = u.creditsBalance + :amount WHERE u.id = :userId")
    int addCredits(@Param("userId") UUID userId, @Param("amount") int amount);
}
