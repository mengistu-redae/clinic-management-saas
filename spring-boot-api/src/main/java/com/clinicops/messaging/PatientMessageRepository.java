package com.clinicops.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PatientMessageRepository extends JpaRepository<PatientMessage, UUID> {

    List<PatientMessage> findAllByPatientIdAndTenantIdOrderByCreatedAtAsc(UUID patientId, UUID tenantId);

    /** Fired as a side effect of staff opening a patient's thread - drives the inbox's own unread count back down. */
    @Modifying
    @Query("UPDATE PatientMessage m SET m.readAt = CURRENT_TIMESTAMP WHERE m.patientId = :patientId AND m.tenantId = :tenantId AND m.senderType = 'patient' AND m.readAt IS NULL")
    void markPatientMessagesReadByStaff(@Param("patientId") UUID patientId, @Param("tenantId") UUID tenantId);

    /** Fired as a side effect of the patient opening their own thread - not currently surfaced in any patient-facing unread UI, kept for symmetry/future use. */
    @Modifying
    @Query("UPDATE PatientMessage m SET m.readAt = CURRENT_TIMESTAMP WHERE m.patientId = :patientId AND m.tenantId = :tenantId AND m.senderType = 'staff' AND m.readAt IS NULL")
    void markStaffMessagesReadByPatient(@Param("patientId") UUID patientId, @Param("tenantId") UUID tenantId);

    /**
     * One row per patient who has at least one message, newest-first -
     * the shared clinic inbox. unreadCount counts only patient-sent
     * messages staff hasn't opened yet (a staff-sent message is never
     * "unread" from the inbox's own point of view).
     */
    @Query(value = """
            SELECT p.id as patientId, (p.first_name || ' ' || p.last_name) as patientName,
                   MAX(m.created_at) as lastMessageAt,
                   COUNT(*) FILTER (WHERE m.sender_type = 'patient' AND m.read_at IS NULL) as unreadCount
            FROM patient_messages m
            JOIN patients p ON m.patient_id = p.id
            WHERE m.tenant_id = :tenantId
            GROUP BY p.id, p.first_name, p.last_name
            ORDER BY MAX(m.created_at) DESC
            """, nativeQuery = true)
    List<MessageInboxEntry> findInbox(@Param("tenantId") UUID tenantId);
}
