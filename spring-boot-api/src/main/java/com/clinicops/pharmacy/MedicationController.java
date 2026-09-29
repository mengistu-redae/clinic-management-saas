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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * The pharmacy catalog + its nested stock batches - same CRUD shape as
 * phase 5's RoomController/AppointmentTypeController (all statuses shown,
 * not active-only - a pharmacist needs to see and reactivate deactivated
 * medications too), plus a nested per-medication stock-batch list/receive/
 * write-off, same convention as ProviderWorkingHoursController nested
 * under ProviderController. pharmacist+clinic_admin throughout - no other
 * role has any pharmacy access.
 */
@RestController
public class MedicationController {

    private static final Set<String> VALID_FORMS = Set.of("tablet", "capsule", "syrup", "injection", "other");
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");
    private static final Set<String> VALID_WRITE_OFF_STATUSES = Set.of("expired", "recalled");
    private static final Set<String> VALID_SCHEDULES =
            Set.of("schedule_i", "schedule_ii", "schedule_iii", "schedule_iv", "schedule_v");

    private final MedicationRepository medicationRepository;
    private final StockBatchRepository stockBatchRepository;

    public MedicationController(MedicationRepository medicationRepository, StockBatchRepository stockBatchRepository) {
        this.medicationRepository = medicationRepository;
        this.stockBatchRepository = stockBatchRepository;
    }

    @GetMapping("/api/clinic/medications")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<Medication> medications(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? medicationRepository.findAllByTenantId(tenantId)
                : medicationRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/clinic/medications/{id}")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public Medication medication(@PathVariable UUID id) {
        return requireOwnedMedication(id, TenantContext.require());
    }

    @PostMapping("/api/clinic/medications")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public Medication createMedication(@Valid @RequestBody CreateMedicationRequest request) {
        if (request.form() != null && !VALID_FORMS.contains(request.form())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "form must be one of " + VALID_FORMS);
        }
        Medication medication = new Medication();
        medication.setTenantId(TenantContext.require());
        medication.setName(request.name());
        if (request.form() != null) {
            medication.setForm(request.form());
        }
        medication.setUnitOfMeasure(request.unitOfMeasure());
        if (request.unitPrice() != null) {
            medication.setUnitPrice(request.unitPrice());
        }
        if (request.reorderThreshold() != null) {
            medication.setReorderThreshold(request.reorderThreshold());
        }
        return medicationRepository.save(medication);
    }

    @PostMapping("/api/clinic/medications/{id}/update")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public Medication updateMedication(@PathVariable UUID id, @RequestBody UpdateMedicationRequest request) {
        Medication medication = requireOwnedMedication(id, TenantContext.require());
        if (request.name() != null) {
            medication.setName(request.name());
        }
        if (request.form() != null) {
            if (!VALID_FORMS.contains(request.form())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "form must be one of " + VALID_FORMS);
            }
            medication.setForm(request.form());
        }
        if (request.unitOfMeasure() != null) {
            medication.setUnitOfMeasure(request.unitOfMeasure());
        }
        if (request.unitPrice() != null) {
            medication.setUnitPrice(request.unitPrice());
        }
        if (request.reorderThreshold() != null) {
            medication.setReorderThreshold(request.reorderThreshold());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            medication.setStatus(request.status());
        }
        return medicationRepository.save(medication);
    }

    /**
     * A dedicated action endpoint, not folded into the generic
     * POST /{id}/update - clinic_admin only (tighter than the catalog's
     * own pharmacist+clinic_admin gate), matching this app's existing
     * convention for a consequential/tighter-gated state change
     * (deactivate/reactivate, stock-batch write-off). See phase 28.
     */
    @PostMapping("/api/clinic/medications/{id}/controlled-substance-schedule")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Medication updateControlledSubstanceSchedule(@PathVariable UUID id, @RequestBody UpdateControlledSubstanceScheduleRequest request) {
        if (request.schedule() != null && !VALID_SCHEDULES.contains(request.schedule())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "schedule must be one of " + VALID_SCHEDULES);
        }
        Medication medication = requireOwnedMedication(id, TenantContext.require());
        medication.setControlledSubstanceSchedule(request.schedule());
        return medicationRepository.save(medication);
    }

    @GetMapping("/api/clinic/medications/{id}/stock-batches")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public List<StockBatch> stockBatches(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedMedication(id, tenantId);
        return stockBatchRepository.findAllByMedicationIdAndTenantId(id, tenantId);
    }

    /** Receives a new batch into stock - quantityOnHand starts equal to quantityReceived. */
    @PostMapping("/api/clinic/medications/{id}/stock-batches")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public StockBatch receiveStockBatch(@PathVariable UUID id, @Valid @RequestBody CreateStockBatchRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedMedication(id, tenantId);
        StockBatch batch = new StockBatch();
        batch.setTenantId(tenantId);
        batch.setMedicationId(id);
        batch.setBatchNumber(request.batchNumber());
        batch.setQuantityReceived(request.quantityReceived());
        batch.setQuantityOnHand(request.quantityReceived());
        batch.setExpiryDate(request.expiryDate());
        return stockBatchRepository.save(batch);
    }

    /** A dedicated action endpoint (not a generic PATCH), same convention as deactivate/reactivate elsewhere - zeroes quantityOnHand and records why. */
    @PostMapping("/api/clinic/medications/{id}/stock-batches/{batchId}/write-off")
    @PreAuthorize("hasAnyRole('PHARMACIST', 'CLINIC_ADMIN')")
    public StockBatch writeOffStockBatch(@PathVariable UUID id, @PathVariable UUID batchId, @Valid @RequestBody WriteOffStockBatchRequest request) {
        if (!VALID_WRITE_OFF_STATUSES.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_WRITE_OFF_STATUSES);
        }
        UUID tenantId = TenantContext.require();
        requireOwnedMedication(id, tenantId);
        StockBatch batch = stockBatchRepository.findByIdAndTenantId(batchId, tenantId)
                .filter(b -> b.getMedicationId().equals(id))
                .orElseThrow(() -> new NoSuchElementException("Stock batch not found: " + batchId));
        batch.setStatus(request.status());
        batch.setWriteOffReason(request.reason());
        batch.setQuantityOnHand(0);
        return stockBatchRepository.save(batch);
    }

    private Medication requireOwnedMedication(UUID id, UUID tenantId) {
        return medicationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
