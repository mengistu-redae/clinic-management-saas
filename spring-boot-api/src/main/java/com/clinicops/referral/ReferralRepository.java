package com.clinicops.referral;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    List<Referral> findAllByTenantId(UUID tenantId);

    Optional<Referral> findByIdAndTenantId(UUID id, UUID tenantId);
}
