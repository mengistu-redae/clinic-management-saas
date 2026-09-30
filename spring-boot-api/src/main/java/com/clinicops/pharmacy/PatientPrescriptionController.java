package com.clinicops.pharmacy;

import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
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
 * Mirrors PatientLabRequestController's own bundling of patient-facing
 * list/create endpoints and the staff review-queue/confirm actions in
 * one file.
 */
@RestController
public class PatientPrescriptionController {

    private final PatientPrescriptionService patientPrescriptionService;
    private final PrescriptionRefillRequestRepository refillRequestRepository;
    private final PatientRepository patientRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public PatientPrescriptionController(
            PatientPrescriptionService patientPrescriptionService,
            PrescriptionRefillRequestRepository refillRequestRepository,
            PatientRepository patientRepository,
            PrescriptionRepository prescriptionRepository,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.patientPrescriptionService = patientPrescriptionService;
        this.refillRequestRepository = refillRequestRepository;
        this.patientRepository = patientRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/my-prescriptions")
    @PreAuthorize("hasRole('PATIENT')")
    public List<MyPrescriptionView> myPrescriptions(@AuthenticationPrincipal Jwt jwt) {
        return patientPrescriptionService.myPrescriptions(jwt);
    }

    @PostMapping("/api/my-prescriptions/{id}/refill-requests")
    @PreAuthorize("hasRole('PATIENT')")
    public PrescriptionRefillRequest createRefillRequest(
            @PathVariable UUID id, @RequestBody(required = false) CreateRefillRequestRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID requestedBy = currentUserService.resolveInternalUserId(jwt);
        String notes = request != null ? request.notes() : null;
        return patientPrescriptionService.createRefillRequest(id, notes, requestedBy);
    }

    @GetMapping("/api/my-refill-requests")
    @PreAuthorize("hasRole('PATIENT')")
    public List<PrescriptionRefillRequest> myRefillRequests(@AuthenticationPrincipal Jwt jwt) {
        return patientPrescriptionService.myRefillRequests(jwt);
    }

    /**
     * Staff review queue - matches DispenseController's own gate.
     * Embeds patientName/medicationName directly (same "controller
     * composes repositories directly for simple read-only aggregation"
     * precedent DispenseController.queue() already sets) - closes a
     * real gap: pharmacist has no other path to resolve either id
     * (PatientController excludes it, and GET /api/pharmacy/queue only
     * covers prescriptions not yet fully dispensed - typically not the
     * case for a refill request).
     */
    @GetMapping("/api/pharmacy/refill-requests")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<RefillRequestQueueEntry> refillRequests(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        List<PrescriptionRefillRequest> refills = status == null || status.isBlank()
                ? refillRequestRepository.findAllByTenantId(tenantId)
                : refillRequestRepository.findAllByTenantIdAndStatus(tenantId, status);
        return refills.stream().map(this::toQueueEntry).toList();
    }

    private RefillRequestQueueEntry toQueueEntry(PrescriptionRefillRequest refill) {
        String patientName = patientRepository.findById(refill.getPatientId())
                .map(p -> p.getFirstName() + " " + p.getLastName())
                .orElse(null);
        String medicationName = prescriptionRepository.findById(refill.getPrescriptionId())
                .map(Prescription::getMedicationName)
                .orElse(null);
        return new RefillRequestQueueEntry(
                refill.getId(), refill.getPrescriptionId(), refill.getPatientId(), patientName, medicationName,
                refill.getRequestedBy(), refill.getCreatedAt(), refill.getNotes(), refill.getStatus(),
                refill.getReviewedBy(), refill.getReviewedAt(), refill.getReviewNotes());
    }

    @PostMapping("/api/pharmacy/refill-requests/{id}/approve")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public PrescriptionRefillRequest approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID reviewedBy = currentUserService.resolveInternalUserId(jwt);
        PrescriptionRefillRequest result = patientPrescriptionService.approve(id, tenantId, reviewedBy);
        phiAccessAuditService.logWrite(tenantId, jwt, "prescription_refill_request", id, result.getPatientId(), "/api/pharmacy/refill-requests/{id}/approve");
        return result;
    }

    @PostMapping("/api/pharmacy/refill-requests/{id}/deny")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public PrescriptionRefillRequest deny(
            @PathVariable UUID id, @RequestBody(required = false) DenyRefillRequestRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID reviewedBy = currentUserService.resolveInternalUserId(jwt);
        String reviewNotes = request != null ? request.reviewNotes() : null;
        PrescriptionRefillRequest result = patientPrescriptionService.deny(id, tenantId, reviewedBy, reviewNotes);
        phiAccessAuditService.logWrite(tenantId, jwt, "prescription_refill_request", id, result.getPatientId(), "/api/pharmacy/refill-requests/{id}/deny");
        return result;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
