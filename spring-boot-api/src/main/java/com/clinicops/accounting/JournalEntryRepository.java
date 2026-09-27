package com.clinicops.accounting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    List<JournalEntry> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<JournalEntry> findByIdAndTenantId(UUID id, UUID tenantId);
}
