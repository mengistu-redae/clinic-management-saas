package com.clinicops.platform;

/** keycloak_org_id is deliberately not editable here - TenantContextFilter matches a staff token's org claim against it. */
public record UpdateClinicRequest(String name) {
}
