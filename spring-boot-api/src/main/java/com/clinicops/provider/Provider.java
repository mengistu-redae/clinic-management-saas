package com.clinicops.provider;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "providers")
@Getter
@Setter
public class Provider extends BaseTenantEntity {

    /** The provider's own login, if any - null for a provider with no portal/staff account yet. */
    @Column(name = "app_user_id")
    private UUID appUserId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String specialty;

    @Column(name = "room_id")
    private UUID roomId;

    @Column(nullable = false)
    private String status = "active";

    /** Phase 13 - compliance tracking, no automated expiry alerting exists yet. */
    @Column(name = "license_number")
    private String licenseNumber;

    @Column(name = "license_expiry")
    private LocalDate licenseExpiry;

    /** full_time, part_time, locum. */
    @Column(name = "employment_status")
    private String employmentStatus;

    /**
     * Describes the file on disk (<uploads-root>/provider-signatures/
     * {id}.{ext}) - the image bytes themselves never live in Postgres, see
     * FileStorageService. Both null together (no signature uploaded) or
     * both set together - ProviderController's upload/remove endpoints
     * keep them in sync, never one without the other.
     */
    @Column(name = "signature_filename")
    private String signatureFilename;

    @Column(name = "signature_content_type")
    private String signatureContentType;
}
