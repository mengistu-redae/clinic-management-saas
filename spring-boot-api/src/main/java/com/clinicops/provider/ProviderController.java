package com.clinicops.provider;

import com.clinicops.filestorage.FileStorageService;
import com.clinicops.room.RoomRepository;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
    private static final Set<String> VALID_EMPLOYMENT_STATUSES = Set.of("full_time", "part_time", "locum");

    /** Content types accepted for a signature upload, mapped to the extension actually written to disk - never the client-supplied filename's own extension, to avoid an unexpected extension reaching disk. */
    private static final Map<String, String> VALID_SIGNATURE_TYPES = Map.of("image/png", ".png", "image/jpeg", ".jpg");
    private static final String SIGNATURE_SUBDIRECTORY = "provider-signatures";

    private final ProviderRepository providerRepository;
    private final RoomRepository roomRepository;
    private final AppUserRepository appUserRepository;
    private final FileStorageService fileStorageService;

    public ProviderController(
            ProviderRepository providerRepository,
            RoomRepository roomRepository,
            AppUserRepository appUserRepository,
            FileStorageService fileStorageService) {
        this.providerRepository = providerRepository;
        this.roomRepository = roomRepository;
        this.appUserRepository = appUserRepository;
        this.fileStorageService = fileStorageService;
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
        if (request.employmentStatus() != null) {
            requireValidEmploymentStatus(request.employmentStatus());
        }

        Provider provider = new Provider();
        provider.setTenantId(tenantId);
        provider.setFullName(request.fullName());
        provider.setSpecialty(request.specialty());
        provider.setRoomId(request.roomId());
        provider.setLicenseNumber(request.licenseNumber());
        provider.setLicenseExpiry(request.licenseExpiry());
        provider.setEmploymentStatus(request.employmentStatus());
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
        if (request.licenseNumber() != null) {
            provider.setLicenseNumber(request.licenseNumber());
        }
        if (request.licenseExpiry() != null) {
            provider.setLicenseExpiry(request.licenseExpiry());
        }
        if (request.employmentStatus() != null) {
            requireValidEmploymentStatus(request.employmentStatus());
            provider.setEmploymentStatus(request.employmentStatus());
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
     * The first file-upload feature in this app - see FileStorageService's
     * own javadoc for the local-disk+volume storage decision. The stored
     * filename is always {@code <providerId><extension>}, the extension
     * derived from the validated content type, never from the
     * client-supplied original filename - deliberately, to keep an
     * unexpected extension (or a path-traversal attempt hidden in a
     * filename) from ever reaching disk. Overwrites any previous
     * signature for this provider - no history kept, matching this app's
     * own "current value only" convention for singleton-per-owner data.
     */
    @PostMapping("/api/providers/{id}/signature")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Provider uploadSignature(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        UUID tenantId = TenantContext.require();
        Provider provider = providerRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));

        String contentType = file.getContentType();
        String extension = VALID_SIGNATURE_TYPES.get(contentType);
        if (extension == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "file must be one of " + VALID_SIGNATURE_TYPES.keySet());
        }
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }

        String filename;
        try {
            filename = fileStorageService.store(SIGNATURE_SUBDIRECTORY, id, extension, file.getInputStream());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file");
        }
        provider.setSignatureFilename(filename);
        provider.setSignatureContentType(contentType);
        return providerRepository.save(provider);
    }

    /** Same 3-role read gate as the provider resource itself. Raw image bytes, not wrapped in JSON. */
    @GetMapping("/api/providers/{id}/signature")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public ResponseEntity<byte[]> getSignature(@PathVariable UUID id) {
        Provider provider = providerRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));
        if (provider.getSignatureFilename() == null) {
            throw new NoSuchElementException("No signature uploaded for provider: " + id);
        }
        byte[] bytes = fileStorageService.read(SIGNATURE_SUBDIRECTORY, provider.getSignatureFilename());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(provider.getSignatureContentType()))
                .body(bytes);
    }

    /** Hard delete of the file itself (FileStorageService.delete) - no soft-remove concept for an uploaded file the way status/severity fields elsewhere in this app use. Idempotent - a provider with no signature returns unchanged. */
    @PostMapping("/api/providers/{id}/signature/remove")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Provider removeSignature(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        Provider provider = providerRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + id));
        if (provider.getSignatureFilename() != null) {
            fileStorageService.delete(SIGNATURE_SUBDIRECTORY, provider.getSignatureFilename());
            provider.setSignatureFilename(null);
            provider.setSignatureContentType(null);
            provider = providerRepository.save(provider);
        }
        return provider;
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

    private void requireValidEmploymentStatus(String employmentStatus) {
        if (!VALID_EMPLOYMENT_STATUSES.contains(employmentStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employmentStatus must be one of " + VALID_EMPLOYMENT_STATUSES);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    /** Spring's own multipart resolver rejects an oversized request before this controller ever sees it - mapped to 400 rather than the default 500, matching every other validation failure here. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleTooLarge(MaxUploadSizeExceededException e) {
        return "Uploaded file is too large";
    }
}
