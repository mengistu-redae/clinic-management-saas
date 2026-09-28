package com.clinicops.immunization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImmunizationRepository extends JpaRepository<Immunization, UUID> {

    List<Immunization> findAllByPatientIdAndTenantId(UUID patientId, UUID tenantId);

    Optional<Immunization> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Backs the visit-summary document (phase 26) - "given at this visit," not the patient's unrelated history. */
    List<Immunization> findAllByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);
}
