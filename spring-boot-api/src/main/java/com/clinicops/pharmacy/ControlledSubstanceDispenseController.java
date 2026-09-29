package com.clinicops.pharmacy;

import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The dual-sign-off workflow for a controlled-substance dispense (phase
 * 28) - same hasAnyRole('PHARMACIST', 'CLINIC_ADMIN') gate as
 * DispenseController throughout; co-sign's own real restriction (a
 * *different* person) is enforced in DispenseService, not the role gate,
 * since both signers hold the same role. No response DTO - returns the
 * raw entity, same "no response DTO" precedent MedicationController
 * itself uses.
 */
@RestController
public class ControlledSubstanceDispenseController {

    private final PendingControlledSubstanceDispenseRepository pendingControlledSubstanceDispenseRepository;
    private final DispenseService dispenseService;
    private final CurrentUserService currentUserService;

    public ControlledSubstanceDispenseController(
            PendingControlledSubstanceDispenseRepository pendingControlledSubstanceDispenseRepository,
            DispenseService dispenseService,
            CurrentUserService currentUserService) {
        this.pendingControlledSubstanceDispenseRepository = pendingControlledSubstanceDispenseRepository;
        this.dispenseService = dispenseService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/pharmacy/controlled-substance-requests")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<PendingControlledSubstanceDispense> requests(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? pendingControlledSubstanceDispenseRepository.findAllByTenantId(tenantId)
                : pendingControlledSubstanceDispenseRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @PostMapping("/api/prescriptions/{id}/controlled-substance-requests")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public PendingControlledSubstanceDispense requestDispense(
            @PathVariable UUID id, @Valid @RequestBody RequestControlledSubstanceDispenseRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID requestedBy = currentUserService.resolveInternalUserId(jwt);
        return dispenseService.requestControlledSubstanceDispense(id, TenantContext.require(), request, requestedBy);
    }

    @PostMapping("/api/pharmacy/controlled-substance-requests/{id}/cosign")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public PendingControlledSubstanceDispense cosign(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID coSignedBy = currentUserService.resolveInternalUserId(jwt);
        return dispenseService.coSignControlledSubstanceDispense(id, TenantContext.require(), coSignedBy);
    }

    @PostMapping("/api/pharmacy/controlled-substance-requests/{id}/reject")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public PendingControlledSubstanceDispense reject(
            @PathVariable UUID id, @RequestBody(required = false) RejectControlledSubstanceDispenseRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID rejectedBy = currentUserService.resolveInternalUserId(jwt);
        String reason = request != null ? request.reason() : null;
        return dispenseService.rejectControlledSubstanceDispense(id, TenantContext.require(), rejectedBy, reason);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InsufficientStockException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInsufficientStock(InsufficientStockException e) {
        return e.getMessage();
    }

    @ExceptionHandler(ClinicalSafetyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleClinicalSafetyConflict(ClinicalSafetyConflictException e) {
        return e.getMessage();
    }
}
