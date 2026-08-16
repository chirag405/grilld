package com.grilld.backend.generation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingsRepository extends JpaRepository<PlatformSetting, String> {
}
