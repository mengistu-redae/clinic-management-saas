package com.clinicops.provider;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderWorkingHoursRepository extends JpaRepository<ProviderWorkingHours, UUID> {

    List<ProviderWorkingHours> findAllByProviderId(UUID providerId);

    List<ProviderWorkingHours> findAllByProviderIdAndDayOfWeek(UUID providerId, short dayOfWeek);

    Optional<ProviderWorkingHours> findByIdAndTenantId(UUID id, UUID tenantId);
}
