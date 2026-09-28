package com.clinicops.accounting;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findAllByTenantId(UUID tenantId);

    List<Account> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Account> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Account> findByTenantIdAndCode(UUID tenantId, String code);
}
