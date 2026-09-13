package com.clinicops.encounter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    List<Prescription> findAllByEncounterId(UUID encounterId);

    /** Spring Data derived delete query - no @Modifying needed for deleteBy...; used for full-list-replace semantics. */
    void deleteAllByEncounterId(UUID encounterId);
}
