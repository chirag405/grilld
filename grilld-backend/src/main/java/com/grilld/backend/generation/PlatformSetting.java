package com.grilld.backend.generation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row of `platform_settings` - a plain key/value table seeded by
 * {@code V1__init_schema.sql} with the cost circuit breaker's
 * {@code daily_spend_cap_usd} and {@code kill_switch_active} (§10.6).
 * Deliberately just a string value, not typed columns per setting - this is
 * the "simplest possible implementation" the spec itself asks for.
 */
@Entity
@Table(name = "platform_settings")
public class PlatformSetting {

    @Id
    private String key;

    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PlatformSetting() {
    }

    public PlatformSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public void updateValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
