package com.clinicops.encounter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PrescriptionRepository extends JpaRepository<Prescription, UUID> {

    List<Prescription> findAllByEncounterId(UUID encounterId);

    /** Spring Data derived delete query - no @Modifying needed for deleteBy...; used for full-list-replace semantics. */
    void deleteAllByEncounterId(UUID encounterId);

    /**
     * The pharmacy dispense queue (com.clinicops.pharmacy.DispenseController)
     * - active prescriptions not yet fully dispensed for this tenant, joined
     * through encounters/appointments for a real patientId/contactName (no
     * JPA relation exists between any of these, same join-in-native-SQL
     * pattern AppointmentRepository.findProviderSchedule already uses).
     * "Not yet fully dispensed" means either no prescribed total was ever
     * set (quantityDispensed IS NULL - always shown until the provider
     * marks it completed/discontinued) or the live dispense-records sum is
     * still short of that total.
     *
     * <p>Also LEFT JOINs {@code patients} for a real {@code patientName} -
     * {@code pharmacist} has no read access to {@code PatientController}
     * (unlike front_desk/clinic_admin/provider), so unlike
     * AppointmentWorklistView's own "resolve patientId to a name via the
     * caller's own GET /api/patients" convention, this queue embeds the
     * name directly, same reasoning contactName is already embedded for a
     * guest booking rather than requiring a second lookup. A guest booking
     * has no patientId, so the join simply yields a null patientName there
     * and the caller falls back to contactName.</p>
     */
    @Query(value = """
            SELECT p.id as id, p.encounter_id as encounterId, a.patient_id as patientId, a.contact_name as contactName,
                   (pt.first_name || ' ' || pt.last_name) as patientName,
                   p.medication_name as medicationName, p.dosage as dosage, p.instructions as instructions,
                   p.route as route, p.frequency as frequency, p.duration as duration,
                   p.quantity_dispensed as quantityPrescribed, p.refills_allowed as refillsAllowed, p.status as status,
                   p.created_at as createdAt,
                   COALESCE((SELECT SUM(d.quantity_dispensed) FROM dispense_records d WHERE d.prescription_id = p.id), 0) as quantityAlreadyDispensed
            FROM prescriptions p
            JOIN encounters e ON p.encounter_id = e.id
            JOIN appointments a ON e.appointment_id = a.id
            LEFT JOIN patients pt ON a.patient_id = pt.id
            WHERE p.tenant_id = :tenantId AND p.status = 'active'
              AND (p.quantity_dispensed IS NULL OR p.quantity_dispensed >
                   COALESCE((SELECT SUM(d2.quantity_dispensed) FROM dispense_records d2 WHERE d2.prescription_id = p.id), 0))
            ORDER BY p.created_at DESC
            """, nativeQuery = true)
    List<PrescriptionDispenseView> findPendingDispense(@Param("tenantId") UUID tenantId);
}
