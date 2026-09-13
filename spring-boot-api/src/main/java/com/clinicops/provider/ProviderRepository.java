package com.clinicops.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {

    Optional<Provider> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Provider> findAllByTenantId(UUID tenantId);

    List<Provider> findAllByTenantIdAndStatus(UUID tenantId, String status);

    /** Resolves the provider row linked to a staff login - see CurrentProviderService. */
    Optional<Provider> findByAppUserIdAndTenantId(UUID appUserId, UUID tenantId);
}
