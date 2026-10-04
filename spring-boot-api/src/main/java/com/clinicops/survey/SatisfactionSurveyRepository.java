package com.clinicops.survey;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SatisfactionSurveyRepository extends JpaRepository<SatisfactionSurvey, UUID> {

    Optional<SatisfactionSurvey> findByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    /** clinic_admin's own aggregate view - average + count over submitted (not pending) rows only. */
    @Query(value = """
            SELECT AVG(rating)::numeric(3,2) as averageRating, COUNT(*) as totalSubmitted
            FROM satisfaction_surveys
            WHERE tenant_id = :tenantId AND submitted_at IS NOT NULL
            """, nativeQuery = true)
    SatisfactionSurveyStats statsForTenant(@Param("tenantId") UUID tenantId);

    /**
     * Newest-first, submitted-only - the patient name resolves through the
     * owning appointment the same "patient first+last, else guest contactName,
     * else a plain fallback" convention AppointmentReminderScheduler/
     * InvoicePdfService already use for the identical lookup.
     */
    @Query(value = """
            SELECT ss.rating as rating, ss.comment as comment, ss.submitted_at as submittedAt,
                   COALESCE(p.first_name || ' ' || p.last_name, a.contact_name, 'Guest') as patientName
            FROM satisfaction_surveys ss
            JOIN appointments a ON ss.appointment_id = a.id
            LEFT JOIN patients p ON a.patient_id = p.id
            WHERE ss.tenant_id = :tenantId AND ss.submitted_at IS NOT NULL
            ORDER BY ss.submitted_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SatisfactionSurveyFeedbackEntry> recentFeedbackForTenant(@Param("tenantId") UUID tenantId, @Param("limit") int limit);
}
