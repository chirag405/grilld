package com.grilld.backend.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    boolean existsByReason(String reason);

    List<CreditTransaction> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
