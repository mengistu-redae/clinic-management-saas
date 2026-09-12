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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs once per authenticated request, after Spring Security has validated
 * the JWT. Reads the {@code organization} claim off a staff token
 * (clinic_admin/provider/front_desk) and resolves it to a {@code clinics.id}
 * via {@link ClinicRepository#findByKeycloakOrgId}, stashing it in a
 * request-scoped {@link TenantContext}. Patient and platform_admin tokens
 * carry no organization claim, so TenantContext stays empty for them - that's
 * expected, not a bug.
 *
 * Also the enforcement point for clinic deactivation: if the resolved
 * clinic's status isn't "active", the request is locked out of the whole API
 * here with a plain 403, before any controller runs.
 *
 * Registered in SecurityConfig with addFilterAfter(...), so it always runs
 * after authentication has populated the SecurityContext.
 */
@Component
public class TenantContextFilter extends OncePerRequestFilter {

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
                Clinic clinic = extractOrgId(jwt)
                        .flatMap(clinicRepository::findByKeycloakOrgId)
                        .orElse(null);

                if (clinic != null) {
                    if (!"active".equals(clinic.getStatus())) {
                        writeForbidden(response, "Clinic account is deactivated");
                        return;
                    }
                    TenantContext.set(clinic.getId());
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // Always clear - threads are pooled and reused across requests.
            TenantContext.clear();
        }
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
    Optional<String> extractOrgId(Jwt jwt) {
        Object claim = jwt.getClaims().get(orgClaimPath);
        // Keycloak's built-in oidc-organization-membership-mapper (backing
        // the "organization" client scope) puts a JSON array of org ALIASES
        // in this claim, e.g. ["demo-clinic"], not an id and not an object
        // keyed by org id - VERIFY THIS against a real decoded token before
        // relying on it (see application.yml's clinicops.tenant.org-claim-path
        // comment). This is why clinics.keycloak_org_id stores the alias, not
        // Keycloak's internal org UUID (see create-demo-clinic.sh). A user
        // can belong to at most one org in this app's model, so we take the
        // first element.
        //
        // The String/Map branches below are kept for older or differently-
        // configured Keycloak versions.
        if (claim instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof String s && !s.isBlank()) {
                return Optional.of(s);
            }
        }
        if (claim instanceof String s && !s.isBlank()) {
            return Optional.of(s);
        }
        if (claim instanceof Map<?, ?> map && !map.isEmpty()) {
            Object first = map.values().iterator().next();
            if (first instanceof Map<?, ?> orgObj && orgObj.get("id") != null) {
                return Optional.of(orgObj.get("id").toString());
            }
        }
        return Optional.empty();
    }
}
