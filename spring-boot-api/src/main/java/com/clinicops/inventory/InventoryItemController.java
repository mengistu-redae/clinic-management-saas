package com.clinicops.inventory;

import com.clinicops.pharmacy.Medication;
import com.clinicops.pharmacy.MedicationRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * The general (non-pharmacy) stock catalog + its nested stock batches -
 * identical CRUD/nested-batch shape as {@code MedicationController}
 * (phase 20), reusing the exact same {@link CreateStockBatchRequest}/
 * {@link WriteOffStockBatchRequest} records rather than a parallel pair -
 * keeps medications and general items symmetric: both can be restocked
 * directly, not just through a {@link PurchaseOrder}. hasAnyRole
 * ('CLINIC_ADMIN', 'FRONT_DESK') throughout - phase 29's own pinned role
 * decision (general operational logistics, not a clinical judgment call
 * or pharmacy-specific concern - deliberately not the pharmacist gate).
 */
@RestController
public class InventoryItemController {

    private static final Set<String> VALID_CATEGORIES = Set.of("clinical_supply", "ppe", "office_supply");
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");
    private static final Set<String> VALID_WRITE_OFF_STATUSES = Set.of("expired", "recalled");

    private final InventoryItemRepository inventoryItemRepository;
    private final StockBatchRepository stockBatchRepository;
    private final MedicationRepository medicationRepository;

    public InventoryItemController(
            InventoryItemRepository inventoryItemRepository,
            StockBatchRepository stockBatchRepository,
            MedicationRepository medicationRepository) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.medicationRepository = medicationRepository;
    }

    @GetMapping("/api/inventory/items")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<InventoryItem> items(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? inventoryItemRepository.findAllByTenantId(tenantId)
                : inventoryItemRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/inventory/items/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public InventoryItem item(@PathVariable UUID id) {
        return requireOwnedItem(id, TenantContext.require());
    }

    @PostMapping("/api/inventory/items")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public InventoryItem createItem(@Valid @RequestBody CreateInventoryItemRequest request) {
        if (!VALID_CATEGORIES.contains(request.category())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category must be one of " + VALID_CATEGORIES);
        }
        InventoryItem item = new InventoryItem();
        item.setTenantId(TenantContext.require());
        item.setName(request.name());
        item.setCategory(request.category());
        item.setUnitOfMeasure(request.unitOfMeasure());
        if (request.unitPrice() != null) {
            item.setUnitPrice(request.unitPrice());
        }
        if (request.reorderThreshold() != null) {
            item.setReorderThreshold(request.reorderThreshold());
        }
        return inventoryItemRepository.save(item);
    }

    @PostMapping("/api/inventory/items/{id}/update")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public InventoryItem updateItem(@PathVariable UUID id, @RequestBody UpdateInventoryItemRequest request) {
        InventoryItem item = requireOwnedItem(id, TenantContext.require());
        if (request.name() != null) {
            item.setName(request.name());
        }
        if (request.category() != null) {
            if (!VALID_CATEGORIES.contains(request.category())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category must be one of " + VALID_CATEGORIES);
            }
            item.setCategory(request.category());
        }
        if (request.unitOfMeasure() != null) {
            item.setUnitOfMeasure(request.unitOfMeasure());
        }
        if (request.unitPrice() != null) {
            item.setUnitPrice(request.unitPrice());
        }
        if (request.reorderThreshold() != null) {
            item.setReorderThreshold(request.reorderThreshold());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            item.setStatus(request.status());
        }
        return inventoryItemRepository.save(item);
    }

    @GetMapping("/api/inventory/items/{id}/stock-batches")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<StockBatch> stockBatches(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedItem(id, tenantId);
        return stockBatchRepository.findAllByInventoryItemIdAndTenantId(id, tenantId);
    }

    @PostMapping("/api/inventory/items/{id}/stock-batches")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public StockBatch receiveStockBatch(@PathVariable UUID id, @Valid @RequestBody CreateStockBatchRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedItem(id, tenantId);
        StockBatch batch = new StockBatch();
        batch.setTenantId(tenantId);
        batch.setInventoryItemId(id);
        batch.setBatchNumber(request.batchNumber());
        batch.setQuantityReceived(request.quantityReceived());
        batch.setQuantityOnHand(request.quantityReceived());
        batch.setExpiryDate(request.expiryDate());
        return stockBatchRepository.save(batch);
    }

    @PostMapping("/api/inventory/items/{id}/stock-batches/{batchId}/write-off")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public StockBatch writeOffStockBatch(@PathVariable UUID id, @PathVariable UUID batchId, @Valid @RequestBody WriteOffStockBatchRequest request) {
        if (!VALID_WRITE_OFF_STATUSES.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_WRITE_OFF_STATUSES);
        }
        UUID tenantId = TenantContext.require();
        requireOwnedItem(id, tenantId);
        StockBatch batch = stockBatchRepository.findByIdAndTenantId(batchId, tenantId)
                .filter(b -> id.equals(b.getInventoryItemId()))
                .orElseThrow(() -> new NoSuchElementException("Stock batch not found: " + batchId));
        batch.setStatus(request.status());
        batch.setWriteOffReason(request.reason());
        batch.setQuantityOnHand(0);
        return stockBatchRepository.save(batch);
    }

    /**
     * Spans both Medication and InventoryItem - for each with a real
     * reorderThreshold, sums quantityOnHand across its own non-terminal
     * (active) stock batches and flags it if the sum is below threshold.
     * Closes phase 20's own documented simplification (low-stock stayed
     * a per-medication client-side badge, not a cross-catalog endpoint).
     *
     * PHARMACIST added (2026-10-02 search/filter audit) - a real gap found
     * wiring Medications.jsx's new "Low stock" filter: a pharmacist is at
     * least as legitimate a consumer of "what needs reordering" as
     * front_desk, and the alternative (fetching every medication's own
     * stock batches up front just to compute this client-side) is exactly
     * the N+1 this shared endpoint already exists to avoid. Same "broader
     * read gate than write gate" precedent MedicationController's own GET
     * endpoints already set for front_desk (phase 35) - write access here
     * is unaffected, still clinic_admin/front_desk only.
     */
    @GetMapping("/api/inventory/reorder-alerts")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PHARMACIST')")
    public List<ReorderAlert> reorderAlerts() {
        UUID tenantId = TenantContext.require();
        List<ReorderAlert> alerts = new ArrayList<>();
        for (Medication medication : medicationRepository.findAllByTenantIdAndStatus(tenantId, "active")) {
            if (medication.getReorderThreshold() <= 0) {
                continue;
            }
            int onHand = stockBatchRepository.findAllByMedicationIdAndTenantId(medication.getId(), tenantId).stream()
                    .filter(b -> "active".equals(b.getStatus()))
                    .mapToInt(StockBatch::getQuantityOnHand)
                    .sum();
            if (onHand < medication.getReorderThreshold()) {
                alerts.add(new ReorderAlert("medication", medication.getId(), medication.getName(), onHand, medication.getReorderThreshold()));
            }
        }
        for (InventoryItem item : inventoryItemRepository.findAllByTenantIdAndStatus(tenantId, "active")) {
            if (item.getReorderThreshold() <= 0) {
                continue;
            }
            int onHand = stockBatchRepository.findAllByInventoryItemIdAndTenantId(item.getId(), tenantId).stream()
                    .filter(b -> "active".equals(b.getStatus()))
                    .mapToInt(StockBatch::getQuantityOnHand)
                    .sum();
            if (onHand < item.getReorderThreshold()) {
                alerts.add(new ReorderAlert("inventory_item", item.getId(), item.getName(), onHand, item.getReorderThreshold()));
            }
        }
        return alerts;
    }

    private InventoryItem requireOwnedItem(UUID id, UUID tenantId) {
        return inventoryItemRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
