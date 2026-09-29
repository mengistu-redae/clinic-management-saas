package com.clinicops.pharmacy;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

/**
 * Same hard-delete CRUD shape as {@code FeePolicyController}/
 * {@code LabRateController} - pure config with a well-defined "missing =
 * no known interaction" fallback. Gate matches {@code DispenseController}'s
 * own `pharmacist`+`clinic_admin` (not the single-`clinic_admin` gate
 * those two other examples happen to use), since interaction pairs are a
 * pharmacy-clinical concern a pharmacist maintains directly.
 */
@RestController
public class DrugInteractionPairController {

    private final DrugInteractionPairRepository drugInteractionPairRepository;
    private final MedicationRepository medicationRepository;

    public DrugInteractionPairController(DrugInteractionPairRepository drugInteractionPairRepository, MedicationRepository medicationRepository) {
        this.drugInteractionPairRepository = drugInteractionPairRepository;
        this.medicationRepository = medicationRepository;
    }

    @GetMapping("/api/pharmacy/drug-interaction-pairs")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<DrugInteractionPair> drugInteractionPairs() {
        return drugInteractionPairRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/api/pharmacy/drug-interaction-pairs/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public DrugInteractionPair drugInteractionPair(@PathVariable UUID id) {
        return drugInteractionPairRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Drug interaction pair not found: " + id));
    }

    @PostMapping("/api/pharmacy/drug-interaction-pairs")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public DrugInteractionPair createDrugInteractionPair(@Valid @RequestBody CreateDrugInteractionPairRequest request) {
        UUID tenantId = TenantContext.require();
        if (request.medicationAId().equals(request.medicationBId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "medicationAId and medicationBId must be different medications");
        }
        requireOwnedMedication(request.medicationAId(), tenantId);
        requireOwnedMedication(request.medicationBId(), tenantId);
        requireNoDuplicatePair(tenantId, request.medicationAId(), request.medicationBId(), null);

        DrugInteractionPair pair = new DrugInteractionPair();
        pair.setTenantId(tenantId);
        pair.setMedicationAId(request.medicationAId());
        pair.setMedicationBId(request.medicationBId());
        pair.setSeverity(request.severity());
        pair.setDescription(request.description());
        return drugInteractionPairRepository.save(pair);
    }

    @PostMapping("/api/pharmacy/drug-interaction-pairs/{id}/update")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public DrugInteractionPair updateDrugInteractionPair(@PathVariable UUID id, @RequestBody UpdateDrugInteractionPairRequest request) {
        DrugInteractionPair pair = drugInteractionPairRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Drug interaction pair not found: " + id));

        if (request.severity() != null) {
            pair.setSeverity(request.severity());
        }
        if (request.description() != null) {
            pair.setDescription(request.description());
        }
        return drugInteractionPairRepository.save(pair);
    }

    @PostMapping("/api/pharmacy/drug-interaction-pairs/{id}/delete")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public DrugInteractionPair deleteDrugInteractionPair(@PathVariable UUID id) {
        DrugInteractionPair pair = drugInteractionPairRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Drug interaction pair not found: " + id));
        drugInteractionPairRepository.delete(pair);
        return pair;
    }

    private void requireOwnedMedication(UUID medicationId, UUID tenantId) {
        if (medicationRepository.findByIdAndTenantId(medicationId, tenantId).isEmpty()) {
            throw new NoSuchElementException("Medication not found: " + medicationId);
        }
    }

    /** A pair is order-independent - (A,B) and (B,A) are the same conflict. */
    private void requireNoDuplicatePair(UUID tenantId, UUID medicationAId, UUID medicationBId, UUID excludingId) {
        boolean duplicate = drugInteractionPairRepository.findAllByTenantId(tenantId).stream()
                .filter(p -> !p.getId().equals(excludingId))
                .anyMatch(p -> isSamePair(p, medicationAId, medicationBId));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An interaction pair for these two medications already exists");
        }
    }

    private boolean isSamePair(DrugInteractionPair pair, UUID medicationAId, UUID medicationBId) {
        boolean sameOrder = Objects.equals(pair.getMedicationAId(), medicationAId) && Objects.equals(pair.getMedicationBId(), medicationBId);
        boolean reversedOrder = Objects.equals(pair.getMedicationAId(), medicationBId) && Objects.equals(pair.getMedicationBId(), medicationAId);
        return sameOrder || reversedOrder;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
