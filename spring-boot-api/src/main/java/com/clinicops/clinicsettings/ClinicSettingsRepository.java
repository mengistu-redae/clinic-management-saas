package com.clinicops.clinicsettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** tenantId is the id itself - plain findById(tenantId) covers every lookup this needs. */
public interface ClinicSettingsRepository extends JpaRepository<ClinicSettings, UUID> {
}
