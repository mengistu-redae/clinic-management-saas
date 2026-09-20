package com.clinicops.phiaudit;

import com.clinicops.user.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Closes the "PHI-access audit: deferred" gap pinned 2026-09-12 - scoped to
 * staff-initiated access only (front_desk/provider/clinic_admin), not a
 * patient viewing their own record: the compliance question this answers is
 * "who on staff looked at/changed this data", not "did the patient check
 * their own chart" (most audit regimes, HIPAA included, don't require
 * logging a person's own access to their own record the same way).
 *
 * Called explicitly at each audited call site (PatientController,
 * EncounterController, the laborder package's staff-facing controllers) -
 * no AOP/annotation magic, matching this codebase's existing style: every
 * cross-cutting concern here (the Notification outbox, TenantContext) is a
 * plain injected bean called by name, not an aspect. Best-effort: a failure
 * writing the log row is caught and logged, never allowed to fail the PHI
 * access/change it was trying to record - same "a flaky provider never
 * fails the primary action" reasoning as NotificationWorker.
 *
 * Not written in the same transaction as the domain read/write it accounts
 * for (the audited service methods have usually already committed by the
 * time control returns to the controller, where this is called) - a
 * genuine limitation, not solved differently here. See CLAUDE.md's known
 * gaps.
 */
@Service
public class PhiAccessAuditService {

    private static final Logger log = LoggerFactory.getLogger(PhiAccessAuditService.class);
    private static final List<String> STAFF_ROLES = List.of("clinic_admin", "front_desk", "provider");

    private final PhiAccessLogRepository repository;
    private final CurrentUserService currentUserService;

    public PhiAccessAuditService(PhiAccessLogRepository repository, CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    public void logRead(UUID tenantId, Jwt jwt, String resourceType, UUID resourceId, UUID patientId, String endpoint) {
        log(tenantId, jwt, resourceType, resourceId, patientId, "read", endpoint);
    }

    public void logWrite(UUID tenantId, Jwt jwt, String resourceType, UUID resourceId, UUID patientId, String endpoint) {
        log(tenantId, jwt, resourceType, resourceId, patientId, "write", endpoint);
    }

    private void log(UUID tenantId, Jwt jwt, String resourceType, UUID resourceId, UUID patientId, String action, String endpoint) {
        try {
            PhiAccessLog entry = new PhiAccessLog();
            entry.setTenantId(tenantId);
            entry.setActorUserId(currentUserService.resolveInternalUserId(jwt));
            entry.setActorEmail(jwt.getClaimAsString("email"));
            entry.setActorRole(extractPrimaryRole(jwt));
            entry.setPatientId(patientId);
            entry.setResourceType(resourceType);
            entry.setResourceId(resourceId);
            entry.setAction(action);
            entry.setEndpoint(endpoint);
            repository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write PHI access log row (tenant={}, resourceType={}, resourceId={}, action={})",
                    tenantId, resourceType, resourceId, action, e);
        }
    }

    private String extractPrimaryRole(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return "unknown";
        }
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) realmAccess.get("roles");
        if (roles == null) {
            return "unknown";
        }
        return STAFF_ROLES.stream().filter(roles::contains).findFirst().orElse("unknown");
    }
}
