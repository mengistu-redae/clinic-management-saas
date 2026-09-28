package com.clinicops.finance;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row per staff {@code AppUser} a clinic has opted into payroll - not
 * every staff login has one. Resolved by email at creation, the same
 * {@code ProviderController.linkLogin} precedent ("no account has ever
 * logged in with that email" 404 if unresolvable) - there's no general
 * staff-directory endpoint in this app to search by, and every staff
 * member manageable here has necessarily logged in at least once already.
 * {@code fullName}/{@code email} are snapshotted at creation for display,
 * since there's nothing to re-resolve them from later without that
 * directory endpoint.
 */
@Entity
@Table(name = "employees")
@Getter
@Setter
public class Employee extends BaseTenantEntity {

    @Column(name = "app_user_id", nullable = false)
    private UUID appUserId;

    @Column(name = "full_name")
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(name = "salary_amount", nullable = false)
    private BigDecimal salaryAmount;

    @Column(nullable = false)
    private String status = "active";
}
