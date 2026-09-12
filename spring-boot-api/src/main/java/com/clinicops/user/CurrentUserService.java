package com.clinicops.user;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Resolves the JWT in front of every request to our internal {@link AppUser}
 * id, provisioning the row on first login via {@link AppUserWriter} - a
 * separate {@code @Transactional} bean, not a same-class call, so the
 * write actually goes through Spring's transactional proxy (see
 * AppUserWriter's javadoc for the reference-project bug this avoids).
 */
@Service
public class CurrentUserService {

    private final AppUserRepository appUserRepository;
    private final AppUserWriter appUserWriter;

    public CurrentUserService(AppUserRepository appUserRepository, AppUserWriter appUserWriter) {
        this.appUserRepository = appUserRepository;
        this.appUserWriter = appUserWriter;
    }

    public UUID resolveInternalUserId(Jwt jwt) {
        return resolveAppUser(jwt).getId();
    }

    public AppUser resolveAppUser(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        return appUserRepository.findByKeycloakUserId(keycloakUserId)
                .orElseGet(() -> appUserWriter.provision(jwt, keycloakUserId));
    }
}
