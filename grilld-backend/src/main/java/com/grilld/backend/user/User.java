package com.grilld.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the `users` table (V1__init_schema.sql). An "entity" - a plain Java
 * class where each field maps to a database column; Hibernate (via Spring Data
 * JPA) handles reading/writing rows for us based on this mapping.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "google_id", nullable = false, unique = true)
    private String googleId;

    private String name;

    @Column(name = "picture_url")
    private String pictureUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Plan plan = Plan.FREE;

    @Column(name = "credits_balance", nullable = false)
    private int creditsBalance = 0; // no free grant - every credit is purchased, see CreditService

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected User() {
        // JPA requires a no-arg constructor; never called directly by our code.
    }

    public User(String email, String googleId) {
        this.email = email;
        this.googleId = googleId;
    }

    /** Refreshed on every login - Google's own copy is the source of truth, not ours. */
    public void updateProfile(String name, String pictureUrl) {
        this.name = name;
        this.pictureUrl = pictureUrl;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getGoogleId() {
        return googleId;
    }

    public String getName() {
        return name;
    }

    public String getPictureUrl() {
        return pictureUrl;
    }

    public Plan getPlan() {
        return plan;
    }

    public int getCreditsBalance() {
        return creditsBalance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public enum Plan {
        FREE, STARTER, BUILDER, PRO, TEAM
    }
}
