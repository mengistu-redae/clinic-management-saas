package com.clinicops.staff;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * A role-neutral roster record - every realm role (clinic_admin/provider/
 * front_desk/pharmacist/accountant/lab_technician/imaging_technologist) can
 * have one, independent of role-specific entities like Provider. Not an
 * authorization source - role here is a display/filter label only, the
 * JWT's realm roles remain the only thing @PreAuthorize ever checks.
 */
@Entity
@Table(name = "staff")
@Getter
@Setter
public class Staff extends BaseTenantEntity {

    /** This staff member's own login, if any - null until linked, same optional-link shape as Provider.appUserId. */
    @Column(name = "app_user_id")
    private UUID appUserId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String status = "active";
}
