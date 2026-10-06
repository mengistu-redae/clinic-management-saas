package com.clinicops.tenant;

import java.util.List;
import java.util.UUID;

/**
 * Holds the current request's tenant (clinic) id, if any, plus (phase 45)
 * the active clinic's clinicGroupId - nullable, set only when that clinic
 * has been linked into a chain for cross-branch patient-record sharing -
 * and the full set of clinics the caller is a Keycloak-org member of
 * ("accessible clinics"), which may contain more than one once a staff
 * member belongs to multiple branches.
 *
 * Deliberately NOT wired into a blanket Hibernate multi-tenant filter.
 * Staff-scoped repository methods should take the tenant id explicitly as a
 * parameter (e.g. findByTenantIdAndId(...)) rather than reading this
 * implicitly deep inside a query, so it's always obvious from the method
 * signature whether an endpoint is tenant-scoped or cross-tenant. The
 * clinicGroupId accessor is the one deliberate exception - the handful of
 * patient-domain call sites that need it pass it explicitly too, same
 * convention, just a second field.
 *
 * Empty (null) for:
 *  - patient tokens (patients are not members of any Organization)
 *  - platform_admin tokens acting across every tenant
 */
public final class TenantContext {

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private record Holder(UUID tenantId, UUID clinicGroupId, List<UUID> accessibleClinicIds) {
    }

    private TenantContext() {
    }

    /** @param accessibleClinicIds every clinic (by id) the caller is a Keycloak-org member of - at least [tenantId] itself. */
    public static void set(UUID tenantId, UUID clinicGroupId, List<UUID> accessibleClinicIds) {
        CURRENT.set(new Holder(tenantId, clinicGroupId, accessibleClinicIds));
    }

    public static UUID get() {
        Holder h = CURRENT.get();
        return h == null ? null : h.tenantId();
    }

    public static UUID require() {
        UUID tenantId = get();
        if (tenantId == null) {
            throw new IllegalStateException(
                "No tenant on this request - this endpoint requires a staff token " +
                "(clinic_admin/provider/front_desk) with an organization membership, " +
                "not a patient or platform_admin token."
            );
        }
        return tenantId;
    }

    /** Null unless the active clinic has been linked into a chain (phase 45) - most callers get null here, that's expected, not a bug. */
    public static UUID clinicGroupId() {
        Holder h = CURRENT.get();
        return h == null ? null : h.clinicGroupId();
    }

    /** Every clinic the caller is a Keycloak-org member of, not just the active one - powers GET /api/me/clinics. Empty for non-staff tokens. */
    public static List<UUID> accessibleClinicIds() {
        Holder h = CURRENT.get();
        return h == null ? List.of() : h.accessibleClinicIds();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
