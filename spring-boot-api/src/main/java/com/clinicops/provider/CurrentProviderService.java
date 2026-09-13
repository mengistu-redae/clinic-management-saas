package com.clinicops.provider;

import com.clinicops.user.CurrentUserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Resolves the calling provider-role user's own {@link Provider} row from
 * their JWT, same "one service per resolution concern" shape as
 * CurrentUserService/PatientProvisioningService. Unlike patients, a
 * Provider row is never auto-provisioned here - linking one to a login is
 * still SQL-only (no provider admin CRUD until phase 5), so a provider
 * account with nothing linked yet is a clear, deliberate 404, not a
 * silent auto-create.
 */
@Service
public class CurrentProviderService {

    private final CurrentUserService currentUserService;
    private final ProviderRepository providerRepository;

    public CurrentProviderService(CurrentUserService currentUserService, ProviderRepository providerRepository) {
        this.currentUserService = currentUserService;
        this.providerRepository = providerRepository;
    }

    public UUID resolveProviderId(Jwt jwt, UUID tenantId) {
        UUID appUserId = currentUserService.resolveInternalUserId(jwt);
        return providerRepository.findByAppUserIdAndTenantId(appUserId, tenantId)
                .map(Provider::getId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No provider profile linked to this account - contact your clinic admin"));
    }
}
