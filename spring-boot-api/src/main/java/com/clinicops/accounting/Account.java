package com.clinicops.accounting;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One row per chart-of-accounts entry. {@link AccountSeedingService} seeds
 * a minimal starter set per tenant on first use (Cash/Service Revenue/
 * Refunds & Allowances - exactly what {@link JournalService}'s own
 * auto-posting logic actually targets); an accountant/clinic_admin can add
 * more via {@link AccountController}. {@code code} is fixed at creation
 * (not editable via update) since {@link JournalService} resolves the
 * starter accounts by code - same "read-only after creation" precedent as
 * LabRate's own testCode.
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
public class Account extends BaseTenantEntity {

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    /** asset, liability, equity, revenue, expense. */
    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String status = "active";
}
