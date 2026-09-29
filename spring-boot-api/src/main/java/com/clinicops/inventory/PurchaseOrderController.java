package com.clinicops.inventory;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A real order placed with a {@link Supplier}, mixing medication and
 * inventory-item lines - same phase-29 role gate as the rest of this
 * package. Receiving is all-or-nothing (every line's full ordered
 * quantity, one new StockBatch each) - no partial-receipt granularity in
 * v1, a documented scope boundary. No update/delete on lines once
 * created - a placed order is a real document, same "immutable once
 * issued" reasoning Invoice/LabOrder line items already use.
 */
@RestController
public class PurchaseOrderController {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final SupplierRepository supplierRepository;
    private final MedicationRepository medicationRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final StockBatchRepository stockBatchRepository;

    public PurchaseOrderController(
            PurchaseOrderRepository purchaseOrderRepository,
            PurchaseOrderLineRepository purchaseOrderLineRepository,
            SupplierRepository supplierRepository,
            MedicationRepository medicationRepository,
            InventoryItemRepository inventoryItemRepository,
            StockBatchRepository stockBatchRepository) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.purchaseOrderLineRepository = purchaseOrderLineRepository;
        this.supplierRepository = supplierRepository;
        this.medicationRepository = medicationRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.stockBatchRepository = stockBatchRepository;
    }

    @GetMapping("/api/inventory/purchase-orders")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<PurchaseOrderWithLines> purchaseOrders() {
        UUID tenantId = TenantContext.require();
        return purchaseOrderRepository.findAllByTenantId(tenantId).stream()
                .map(this::withLines)
                .toList();
    }

    @GetMapping("/api/inventory/purchase-orders/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public PurchaseOrderWithLines purchaseOrder(@PathVariable UUID id) {
        return withLines(requireOwnedOrder(id, TenantContext.require()));
    }

    @PostMapping("/api/inventory/purchase-orders")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public PurchaseOrderWithLines createPurchaseOrder(@Valid @RequestBody CreatePurchaseOrderRequest request) {
        UUID tenantId = TenantContext.require();
        supplierRepository.findByIdAndTenantId(request.supplierId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Supplier not found: " + request.supplierId()));

        for (CreatePurchaseOrderRequest.PurchaseOrderLineRequest line : request.lines()) {
            validateLineOwner(tenantId, line.medicationId(), line.inventoryItemId());
        }

        PurchaseOrder order = new PurchaseOrder();
        order.setTenantId(tenantId);
        order.setSupplierId(request.supplierId());
        order.setNotes(request.notes());
        order = purchaseOrderRepository.save(order);

        for (CreatePurchaseOrderRequest.PurchaseOrderLineRequest lineRequest : request.lines()) {
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setTenantId(tenantId);
            line.setPurchaseOrderId(order.getId());
            line.setMedicationId(lineRequest.medicationId());
            line.setInventoryItemId(lineRequest.inventoryItemId());
            line.setQuantityOrdered(lineRequest.quantityOrdered());
            line.setUnitCost(lineRequest.unitCost());
            purchaseOrderLineRepository.save(line);
        }
        return withLines(order);
    }

    @PostMapping("/api/inventory/purchase-orders/{id}/receive")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public PurchaseOrderWithLines receive(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        PurchaseOrder order = requireOwnedOrder(id, tenantId);
        if (!"ordered".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase order has already been " + order.getStatus());
        }
        for (PurchaseOrderLine line : purchaseOrderLineRepository.findAllByPurchaseOrderId(id)) {
            StockBatch batch = new StockBatch();
            batch.setTenantId(tenantId);
            batch.setMedicationId(line.getMedicationId());
            batch.setInventoryItemId(line.getInventoryItemId());
            batch.setQuantityReceived(line.getQuantityOrdered());
            batch.setQuantityOnHand(line.getQuantityOrdered());
            stockBatchRepository.save(batch);
        }
        order.setStatus("received");
        order.setReceivedAt(Instant.now());
        return withLines(purchaseOrderRepository.save(order));
    }

    @PostMapping("/api/inventory/purchase-orders/{id}/cancel")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public PurchaseOrderWithLines cancel(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        PurchaseOrder order = requireOwnedOrder(id, tenantId);
        if (!"ordered".equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This purchase order has already been " + order.getStatus());
        }
        order.setStatus("cancelled");
        return withLines(purchaseOrderRepository.save(order));
    }

    private void validateLineOwner(UUID tenantId, UUID medicationId, UUID inventoryItemId) {
        boolean hasMedication = medicationId != null;
        boolean hasItem = inventoryItemId != null;
        if (hasMedication == hasItem) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each line must set exactly one of medicationId/inventoryItemId");
        }
        if (hasMedication) {
            medicationRepository.findByIdAndTenantId(medicationId, tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Medication not found: " + medicationId));
        } else {
            inventoryItemRepository.findByIdAndTenantId(inventoryItemId, tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Inventory item not found: " + inventoryItemId));
        }
    }

    private PurchaseOrderWithLines withLines(PurchaseOrder order) {
        return new PurchaseOrderWithLines(order, purchaseOrderLineRepository.findAllByPurchaseOrderId(order.getId()));
    }

    private PurchaseOrder requireOwnedOrder(UUID id, UUID tenantId) {
        return purchaseOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Purchase order not found: " + id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
