package com.clinicops.insurance;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Patient-level, not encounter/appointment-level - a standing coverage
 * record, same shape precedent as {@link com.clinicops.allergy.Allergy}: no
 * delete endpoint, a mistaken entry gets corrected by adding a new row and
 * marking the old one {@code inactive} via {@link #status}, never erased.
 *
 * A patient can hold more than one policy at once ({@link #rank} primary vs.
 * secondary, for coordination of benefits) - deliberately a separate table
 * from {@code patients.insurance_member_id} (a single flat field dating back
 * to phase 1), not a replacement for it; that column is left untouched.
 */
@Entity
@Table(name = "insurance_policies")
@Getter
@Setter
public class InsurancePolicy extends BaseTenantEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "payer_name", nullable = false)
    private String payerName;

    @Column(name = "member_id", nullable = false)
    private String memberId;

    @Column(name = "group_number")
    private String groupNumber;

    @Column(name = "plan_type")
    private String planType;

    /** primary, secondary. */
    @Column(nullable = false)
    private String rank = "primary";

    @Column(name = "subscriber_name")
    private String subscriberName;

    /** self, spouse, child, other. */
    @Column(name = "relationship_to_subscriber", nullable = false)
    private String relationshipToSubscriber = "self";

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    /** active, inactive. */
    @Column(nullable = false)
    private String status = "active";

    /** Not on BaseTenantEntity (only createdAt is) - set on every update, same convention as Allergy.updatedAt. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
