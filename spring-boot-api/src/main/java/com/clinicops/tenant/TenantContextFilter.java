package com.clinicops.tenant;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs once per authenticated request, after Spring Security has validated
 * the JWT. Reads the {@code organization} claim off a staff token
 * (clinic_admin/provider/front_desk) and resolves every alias in it to its
 * {@code Clinic} row - the caller's full set of "accessible clinics" (phase
 * 45: a staff member can now be a Keycloak-org member of more than one
 * clinic). Which one is active for this particular request is picked via
 * the optional {@code X-Active-Clinic-Id} header, validated against that
 * same accessible set, and stashed in a request-scoped {@link TenantContext}.
 * Patient and platform_admin tokens carry no organization claim, so
 * TenantContext stays empty for them - that's expected, not a bug.
 *
 * Also the enforcement point for clinic deactivation: if the resolved
 * active clinic's status isn't "active", the request is locked out of the
 * whole API here with a plain 403, before any controller runs.
 *
 * Registered in SecurityConfig with addFilterAfter(...), so it always runs
 * after authentication has populated the SecurityContext.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String ACTIVE_CLINIC_HEADER = "X-Active-Clinic-Id";

    private final ClinicRepository clinicRepository;
    private final String orgClaimPath;

    public TenantContextFilter(
            ClinicRepository clinicRepository,
            @Value("${clinicops.tenant.org-claim-path}") String orgClaimPath) {
        this.clinicRepository = clinicRepository;
        this.orgClaimPath = orgClaimPath;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                List<Clinic> accessibleClinics = extractOrgAliases(jwt).stream()
                        .map(clinicRepository::findByKeycloakOrgId)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toList();

                if (!accessibleClinics.isEmpty()) {
                    Clinic active = resolveActiveClinic(request, accessibleClinics);
                    if (active == null) {
                        // X-Active-Clinic-Id named a clinic the caller isn't a member of -
                        // fail closed, the header is fully client-controlled.
                        writeForbidden(response, "Not a member of the requested clinic");
                        return;
                    }
                    if (!"active".equals(active.getStatus())) {
                        writeForbidden(response, "Clinic account is deactivated");
                        return;
                    }
                    TenantContext.set(
                            active.getId(),
                            active.getClinicGroupId(),
                            accessibleClinics.stream().map(Clinic::getId).toList());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // Always clear - threads are pooled and reused across requests.
            TenantContext.clear();
        }
    }

    /**
     * Picks the active clinic for this request: the one named by {@code
     * X-Active-Clinic-Id} if present (must be a member of it), else the
     * caller's first/only accessible clinic - preserving today's exact
     * behavior for every single-branch staff login and every API caller
     * that doesn't yet know about this header. Returns null only for an
     * explicit header naming a clinic the caller isn't a member of.
     */
    private Clinic resolveActiveClinic(HttpServletRequest request, List<Clinic> accessibleClinics) {
        String requested = request.getHeader(ACTIVE_CLINIC_HEADER);
        if (requested == null || requested.isBlank()) {
            return accessibleClinics.get(0);
        }
        UUID requestedId;
        try {
            requestedId = UUID.fromString(requested.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return accessibleClinics.stream()
                .filter(c -> c.getId().equals(requestedId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Writes a plain 403 and ends the request. Deliberately not
     * response.sendError(...): that triggers the servlet container's forward
     * to /error, a fresh dispatch back through this filter chain.
     */
    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    // Package-private (not private) so TenantContextFilterTest can exercise
    // the claim-shape parsing directly without standing up a full filter
    // chain / mocked SecurityContext.
    List<String> extractOrgAliases(Jwt jwt) {
        Object claim = jwt.getClaims().get(orgClaimPath);
        // Keycloak's built-in oidc-organization-membership-mapper (backing
        // the "organization" client scope) puts a JSON array of org ALIASES
        // in this claim, e.g. ["demo-clinic"], not an id and not an object
        // keyed by org id - VERIFY THIS against a real decoded token before
        // relying on it (see application.yml's clinicops.tenant.org-claim-path
        // comment). This is why clinics.keycloak_org_id stores the alias, not
        // Keycloak's internal org UUID (see create-demo-clinic.sh).
        //
        // Phase 45: a caller can belong to MORE than one org (multiple
        // clinic branches) - Keycloak already supports this natively, this
        // method just stopped discarding every element but the first. The
        // String/Map branches below are kept for older or differently
        // -configured Keycloak versions, where at most one org is ever
        // expressed.
        if (claim instanceof List<?> list && !list.isEmpty()) {
            List<String> aliases = new ArrayList<>();
            for (Object element : list) {
                if (element instanceof String s && !s.isBlank()) {
                    aliases.add(s);
                }
            }
            return aliases;
        }
        if (claim instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        if (claim instanceof Map<?, ?> map && !map.isEmpty()) {
            Object first = map.values().iterator().next();
            if (first instanceof Map<?, ?> orgObj && orgObj.get("id") != null) {
                return List.of(orgObj.get("id").toString());
            }
        }
        return List.of();
    }
}
