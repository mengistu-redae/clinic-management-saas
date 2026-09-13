package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LabOrderRepository extends JpaRepository<LabOrder, UUID> {

    Optional<LabOrder> findByIdAndTenantId(UUID id, UUID tenantId);

    List<LabOrder> findAllByTenantId(UUID tenantId);

    List<LabOrder> findAllByTenantIdAndStatus(UUID tenantId, String status);

    boolean existsByOrderRef(String orderRef);

    boolean existsByTenantIdAndClinicRef(UUID tenantId, String clinicRef);

    /** Public track-by-ref lookup - not tenant-scoped, mirrors Appointment.findByAppointmentRef. */
    Optional<LabOrder> findByOrderRef(String orderRef);

    // ---- ownership-scoped ("my lab orders"): a patient token carries no
    // tenant, so these are scoped by customerUserId / a resolved encounter
    // id set instead. ----

    List<LabOrder> findAllByCustomerUserId(UUID customerUserId);

    List<LabOrder> findAllByEncounterIdIn(List<UUID> encounterIds);
}
