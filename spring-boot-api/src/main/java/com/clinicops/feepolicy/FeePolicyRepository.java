package com.clinicops.feepolicy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeePolicyRepository extends JpaRepository<FeePolicy, UUID> {

    /** A specific provider's override tiers, if the clinic has configured any. */
    List<FeePolicy> findAllByTenantIdAndProviderId(UUID tenantId, UUID providerId);

    /** The clinic-wide default tiers (providerId IS NULL). */
    List<FeePolicy> findAllByTenantIdAndProviderIdIsNull(UUID tenantId);

    List<FeePolicy> findAllByTenantId(UUID tenantId);

    Optional<FeePolicy> findByIdAndTenantId(UUID id, UUID tenantId);
}
