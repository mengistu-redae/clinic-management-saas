package com.clinicops.phiaudit;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Append-only - no update/delete path exists anywhere in the app, matching
 * an audit trail's own "never rewritten" nature. `createdAt` (from
 * BaseTenantEntity) doubles as the access timestamp, no separate column
 * needed. `patientId` is nullable because a guest-channel appointment/
 * encounter has no Patient row at all (contactName only) - the access still
 * gets logged via resourceType/resourceId, just with no patient to link.
 */
@Entity
@Table(name = "phi_access_log")
@Getter
@Setter
public class PhiAccessLog extends BaseTenantEntity {

    @Column(name = "actor_user_id", nullable = false)
    private UUID actorUserId;

    @Column(name = "actor_email", nullable = false)
    private String actorEmail;

    @Column(name = "actor_role", nullable = false)
    private String actorRole;

    @Column(name = "patient_id")
    private UUID patientId;

    /** "patient", "encounter", "prescription", or "lab_order". */
    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    /** "read" or "write". */
    @Column(nullable = false)
    private String action;

    /** The request path, e.g. "/api/patients/{id}" - captured as the literal mapping, not the interpolated URL, so log rows group cleanly by endpoint. */
    @Column(nullable = false)
    private String endpoint;
}
