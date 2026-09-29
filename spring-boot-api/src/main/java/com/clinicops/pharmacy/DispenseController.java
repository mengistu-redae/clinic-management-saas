package com.clinicops.pharmacy;

import com.clinicops.encounter.PrescriptionDispenseView;
import com.clinicops.encounter.PrescriptionRepository;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The pharmacist's own worklist - active prescriptions not yet fully
 * dispensed (see PrescriptionRepository.findPendingDispense) - plus the
 * dispense action itself. Split out from MedicationController the same
 * way ProviderWorkingHoursController is its own class nested under
 * Provider - this resource is about Prescription, not Medication.
 */
@RestController
public class DispenseController {

    private final PrescriptionRepository prescriptionRepository;
    private final DispenseService dispenseService;
    private final DispenseRecordRepository dispenseRecordRepository;
    private final MedicationRepository medicationRepository;
    private final CurrentUserService currentUserService;

    public DispenseController(
            PrescriptionRepository prescriptionRepository,
            DispenseService dispenseService,
            DispenseRecordRepository dispenseRecordRepository,
            MedicationRepository medicationRepository,
            CurrentUserService currentUserService) {
        this.prescriptionRepository = prescriptionRepository;
        this.dispenseService = dispenseService;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.medicationRepository = medicationRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/pharmacy/queue")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<PrescriptionQueueEntry> queue() {
        UUID tenantId = TenantContext.require();
        List<Medication> activeMedications = medicationRepository.findAllByTenantIdAndStatus(tenantId, "active");
        return prescriptionRepository.findPendingDispense(tenantId).stream()
                .map(view -> toQueueEntry(view, activeMedications))
                .toList();
    }

    private PrescriptionQueueEntry toQueueEntry(PrescriptionDispenseView view, List<Medication> activeMedications) {
        return new PrescriptionQueueEntry(
                view.getId(), view.getEncounterId(), view.getPatientId(), view.getContactName(),
                view.getPatientName(), view.getMedicationName(), view.getDosage(), view.getInstructions(),
                view.getRoute(), view.getFrequency(), view.getDuration(), view.getQuantityPrescribed(),
                view.getRefillsAllowed(), view.getStatus(), view.getCreatedAt(), view.getQuantityAlreadyDispensed(),
                suggestMedication(view.getMedicationName(), activeMedications));
    }

    /** A deliberately simple bidirectional case-insensitive substring match, same shape phase 27's own allergy-conflict check already uses - not real fuzzy matching, first match wins. */
    private UUID suggestMedication(String prescriptionMedicationName, List<Medication> activeMedications) {
        if (prescriptionMedicationName == null || prescriptionMedicationName.isBlank()) {
            return null;
        }
        String needle = prescriptionMedicationName.toLowerCase();
        for (Medication medication : activeMedications) {
            String candidate = medication.getName().toLowerCase();
            if (needle.contains(candidate) || candidate.contains(needle)) {
                return medication.getId();
            }
        }
        return null;
    }

    /** Phase 31 - closes a real gap: no way to read a DispenseRecord existed before a billing flow needed to look one up. */
    @GetMapping("/api/prescriptions/{id}/dispense-records")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<DispenseRecord> dispenseRecords(@PathVariable UUID id) {
        return dispenseRecordRepository.findAllByPrescriptionIdAndTenantId(id, TenantContext.require());
    }

    @PostMapping("/api/prescriptions/{id}/dispense")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public DispenseRecord dispense(@PathVariable UUID id, @Valid @RequestBody DispenseRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID dispensedBy = currentUserService.resolveInternalUserId(jwt);
        return dispenseService.dispense(id, TenantContext.require(), request, dispensedBy);
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
