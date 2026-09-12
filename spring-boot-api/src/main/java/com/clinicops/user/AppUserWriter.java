package com.clinicops.user;

import com.clinicops.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write side of provisioning an {@link AppUser} on first login - a
 * genuinely separate bean from {@link CurrentUserService}, not a
 * same-class {@code this.provision(...)} call. That distinction matters:
 * the reference bus-ticketing-saas project's equivalent
 * (CurrentUserService.provision) is annotated {@code @Transactional} but
 * called via plain self-invocation from within the same class, which
 * silently skips the Spring proxy no matter the method's visibility -
 * found while reading that code for this phase, and deliberately not
 * repeated here.
 */
@Service
public class AppUserWriter {

    private final AppUserRepository appUserRepository;

    public AppUserWriter(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public AppUser provision(Jwt jwt, String keycloakUserId) {
        AppUser user = new AppUser();
        user.setKeycloakUserId(keycloakUserId);
        // Set by TenantContextFilter from the org claim for staff tokens;
        // stays null for patient/platform_admin tokens, same as Clinic.
        user.setTenantId(TenantContext.get());
        user.setEmail(jwt.getClaimAsString("email"));
        user.setDisplayName(jwt.getClaimAsString("name"));

        try {
            return appUserRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent first requests from the same brand-new user
            // both missed CurrentUserService's own lookup and raced to
            // insert - keycloak_user_id's unique constraint lets exactly one
            // win. Fall back to whichever row actually landed instead of
            // failing the request.
            return appUserRepository.findByKeycloakUserId(keycloakUserId).orElseThrow(() -> e);
        }
    }
}
