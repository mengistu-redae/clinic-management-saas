package com.clinicops.provider;

import com.clinicops.room.RoomRepository;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Plain CRUD - no Writer-bean split (see CLAUDE.md's phase 5 write-up):
 * every write here is a single-row save with no cross-bean self
 * -invocation or lock/race concern, so a bare repository call from the
 * controller is enough, same as the reference project's BusController/
 * RouteController.
 */
@RestController
public class ProviderController {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final ProviderRepository providerRepository;
    private final RoomRepository roomRepository;
    private final AppUserRepository appUserRepository;

    public ProviderController(ProviderRepository providerRepository, RoomRepository roomRepository, AppUserRepository appUserRepository) {
        this.providerRepository = providerRepository;
        this.roomRepository = roomRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/api/providers")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<Provider> providers(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? providerRepository.findAllByTenantId(tenantId)
                : providerRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/providers/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public Provider provider(@PathVariable UUID id) {
        return providerRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));
    }

    @PostMapping("/api/providers")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Provider createProvider(@Valid @RequestBody CreateProviderRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedRoomOrNull(request.roomId(), tenantId);

        Provider provider = new Provider();
        provider.setTenantId(tenantId);
        provider.setFullName(request.fullName());
        provider.setSpecialty(request.specialty());
        provider.setRoomId(request.roomId());
        return providerRepository.save(provider);
    }

    /** Partial update - only non-null request fields are applied. Setting status to "inactive"/"active" deactivates/reactivates - no separate endpoint. */
    @PostMapping("/api/providers/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Provider updateProvider(@PathVariable UUID id, @RequestBody UpdateProviderRequest request) {
        UUID tenantId = TenantContext.require();
        Provider provider = providerRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));

        if (request.fullName() != null) {
            provider.setFullName(request.fullName());
        }
        if (request.specialty() != null) {
            provider.setSpecialty(request.specialty());
        }
        if (request.roomId() != null) {
            requireOwnedRoomOrNull(request.roomId(), tenantId);
            provider.setRoomId(request.roomId());
        }
        if (request.status() != null) {
            requireValidStatus(request.status());
            provider.setStatus(request.status());
        }
        return providerRepository.save(provider);
    }

    /** Links this provider to an already-provisioned AppUser (someone who has logged in at least once) - no Keycloak call needed. */
    @PostMapping("/api/providers/{id}/link-login")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Provider linkLogin(@PathVariable UUID id, @Valid @RequestBody LinkProviderLoginRequest request) {
        Provider provider = providerRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));
        AppUser appUser = appUserRepository.findFirstByEmail(request.email())
                .orElseThrow(() -> new NoSuchElementException(
                        "No account has ever logged in with that email - they must log in once first: " + request.email()));
        provider.setAppUserId(appUser.getId());
        return providerRepository.save(provider);
    }

    @PostMapping("/api/providers/{id}/unlink-login")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Provider unlinkLogin(@PathVariable UUID id) {
        Provider provider = providerRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));
        provider.setAppUserId(null);
        return providerRepository.save(provider);
    }

    /**
     * Public - a patient/guest needs some way to discover a clinic's
     * providers before booking one, since GET /api/clinics/{id}/
     * availability already requires a providerId. Mirrors
     * AppointmentTypeController's own publicAppointmentTypes shape
     * exactly. Active only - inactive providers aren't offered for new
     * bookings.
     */
    @GetMapping("/api/clinics/{clinicId}/providers")
    public List<ProviderDirectoryView> publicProviders(@PathVariable UUID clinicId) {
        return providerRepository.findAllByTenantIdAndStatus(clinicId, "active").stream()
                .map(ProviderDirectoryView::from)
                .toList();
    }

    private void requireOwnedRoomOrNull(UUID roomId, UUID tenantId) {
        if (roomId != null && roomRepository.findByIdAndTenantId(roomId, tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "roomId does not belong to this clinic: " + roomId);
        }
    }

    private void requireValidStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
