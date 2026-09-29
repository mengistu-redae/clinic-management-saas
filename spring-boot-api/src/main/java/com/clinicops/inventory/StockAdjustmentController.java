package com.clinicops.inventory;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * A generic, append-only correction against a {@link StockBatch} owned by
 * either a Medication or an InventoryItem - only the batch id is ever
 * needed, so one controller covers both owner types. Same phase-29 role
 * gate as the rest of this package.
 */
@RestController
public class StockAdjustmentController {

    private static final Set<String> VALID_REASONS = Set.of("used", "wasted", "expired", "correction", "other");

    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockBatchRepository stockBatchRepository;
    private final CurrentUserService currentUserService;

    public StockAdjustmentController(
            StockAdjustmentRepository stockAdjustmentRepository,
            StockBatchRepository stockBatchRepository,
            CurrentUserService currentUserService) {
        this.stockAdjustmentRepository = stockAdjustmentRepository;
        this.stockBatchRepository = stockBatchRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/inventory/stock-batches/{batchId}/adjustments")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<StockAdjustment> adjustments(@PathVariable UUID batchId) {
        UUID tenantId = TenantContext.require();
        requireOwnedBatch(batchId, tenantId);
        return stockAdjustmentRepository.findAllByStockBatchIdAndTenantId(batchId, tenantId);
    }

    @PostMapping("/api/inventory/stock-batches/{batchId}/adjustments")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public StockAdjustment createAdjustment(@PathVariable UUID batchId, @Valid @RequestBody CreateStockAdjustmentRequest request, @AuthenticationPrincipal Jwt jwt) {
        if (!VALID_REASONS.contains(request.reason())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason must be one of " + VALID_REASONS);
        }
        UUID tenantId = TenantContext.require();
        StockBatch batch = requireOwnedBatch(batchId, tenantId);

        int newQuantity = batch.getQuantityOnHand() + request.quantityDelta();
        if (newQuantity < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This would take quantityOnHand negative (" + batch.getQuantityOnHand() + " on hand, " + request.quantityDelta() + " requested)");
        }
        batch.setQuantityOnHand(newQuantity);
        if (newQuantity == 0) {
            batch.setStatus("depleted");
        }
        stockBatchRepository.save(batch);

        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setTenantId(tenantId);
        adjustment.setStockBatchId(batchId);
        adjustment.setQuantityDelta(request.quantityDelta());
        adjustment.setReason(request.reason());
        adjustment.setAdjustedBy(currentUserService.resolveInternalUserId(jwt));
        adjustment.setNotes(request.notes());
        return stockAdjustmentRepository.save(adjustment);
    }

    private StockBatch requireOwnedBatch(UUID batchId, UUID tenantId) {
        return stockBatchRepository.findByIdAndTenantId(batchId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Stock batch not found: " + batchId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
