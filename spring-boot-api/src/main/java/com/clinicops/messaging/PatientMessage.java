package com.clinicops.messaging;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One ongoing thread per patient (the user's own pinned decision) - no
 * separate "thread" entity, this row's own accumulation under one
 * {@code patientId} *is* the thread, same "flat, no wrapper entity"
 * simplification {@link com.clinicops.medicalhistory.MedicalHistory}'s
 * own singleton-row shape already demonstrates for a different reason.
 * Genuinely append-only - no update/delete endpoint, matching this app's
 * own precedent for every other audit-weight correspondence table
 * (ConsentRecord/PhiAccessLog/DispenseRecord).
 */
@Entity
@Table(name = "patient_messages")
@Getter
@Setter
public class PatientMessage extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    /** patient, staff. */
    @Column(name = "sender_type", nullable = false)
    private String senderType;

    /** The AppUser.id of whoever actually sent it - resolved the same way for both a patient and a staff sender. */
    @Column(name = "sender_user_id")
    private UUID senderUserId;

    @Column(nullable = false)
    private String body;

    /** Set when the *opposite* party has viewed it - drives the staff inbox's own unread count. Null for the sender's own messages, naturally. */
    @Column(name = "read_at")
    private Instant readAt;
}
