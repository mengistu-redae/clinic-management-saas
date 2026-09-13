package com.clinicops.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByKeycloakUserId(String keycloakUserId);

    /**
     * Used by ProviderController's link-login endpoint - `email` has no
     * unique constraint (see AppUser's own javadoc), so `findFirst` (not a
     * plain `findBy`) avoids an IncorrectResultSizeDataAccessException if
     * more than one row happens to share an address.
     */
    Optional<AppUser> findFirstByEmail(String email);
}
