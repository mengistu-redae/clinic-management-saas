package com.clinicops.patient;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "patients")
@Getter
@Setter
public class Patient extends BaseTenantEntity {

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String phone;

    private String email;

    @Column(name = "national_id")
    private String nationalId;

    @Column(name = "insurance_member_id")
    private String insuranceMemberId;

    /**
     * Set only for a patient who booked through the portal - links this
     * clinic's own record of them back to their Keycloak-backed AppUser.
     * Null for a walk-in registered by front-desk staff with no portal
     * account. See PatientProvisioningService.
     */
    @Column(name = "app_user_id")
    private UUID appUserId;

    /**
     * Nullable - denormalized copy of the creating clinic's own
     * clinicGroupId at creation time (phase 45), never a live join. Null for
     * every patient created at a standalone clinic (today's default). When
     * set, this is the key that lets a sibling branch in the same group find
     * and reuse this same Patient row instead of creating a duplicate - see
     * PatientRepository's group-aware lookups and PatientProvisioningService.
     */
    @Column(name = "clinic_group_id")
    private UUID clinicGroupId;
}
