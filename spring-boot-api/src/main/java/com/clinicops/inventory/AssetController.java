package com.clinicops.inventory;

import com.clinicops.room.RoomRepository;
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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Equipment & asset tracking (phase 30) - same phase-29 role gate as the
 * rest of com.clinicops.inventory (clinic_admin+front_desk, general
 * operational logistics, not a clinical judgment or pharmacy-specific
 * concern). No delete endpoint - status transitions only, same "real
 * inventory/audit weight" precedent as Medication/Allergy.
 */
@RestController
public class AssetController {

    private static final Set<String> VALID_STATUSES = Set.of("in_service", "under_maintenance", "retired", "disposed");

    private final AssetRepository assetRepository;
    private final AssetMaintenanceRecordRepository assetMaintenanceRecordRepository;
    private final RoomRepository roomRepository;
    private final CurrentUserService currentUserService;

    public AssetController(
            AssetRepository assetRepository,
            AssetMaintenanceRecordRepository assetMaintenanceRecordRepository,
            RoomRepository roomRepository,
            CurrentUserService currentUserService) {
        this.assetRepository = assetRepository;
        this.assetMaintenanceRecordRepository = assetMaintenanceRecordRepository;
        this.roomRepository = roomRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/inventory/assets")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<Asset> assets(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? assetRepository.findAllByTenantId(tenantId)
                : assetRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/inventory/assets/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Asset asset(@PathVariable UUID id) {
        return requireOwnedAsset(id, TenantContext.require());
    }

    @PostMapping("/api/inventory/assets")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Asset createAsset(@Valid @RequestBody CreateAssetRequest request) {
        Asset asset = new Asset();
        asset.setTenantId(TenantContext.require());
        asset.setName(request.name());
        asset.setSerialNumber(request.serialNumber());
        asset.setPurchaseDate(request.purchaseDate());
        asset.setPurchasePrice(request.purchasePrice());
        asset.setWarrantyExpiry(request.warrantyExpiry());
        asset.setNotes(request.notes());
        return assetRepository.save(asset);
    }

    @PostMapping("/api/inventory/assets/{id}/update")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Asset updateAsset(@PathVariable UUID id, @RequestBody UpdateAssetRequest request) {
        Asset asset = requireOwnedAsset(id, TenantContext.require());
        if (request.name() != null) {
            asset.setName(request.name());
        }
        if (request.serialNumber() != null) {
            asset.setSerialNumber(request.serialNumber());
        }
        if (request.purchaseDate() != null) {
            asset.setPurchaseDate(request.purchaseDate());
        }
        if (request.purchasePrice() != null) {
            asset.setPurchasePrice(request.purchasePrice());
        }
        if (request.warrantyExpiry() != null) {
            asset.setWarrantyExpiry(request.warrantyExpiry());
        }
        if (request.notes() != null) {
            asset.setNotes(request.notes());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            asset.setStatus(request.status());
        }
        return assetRepository.save(asset);
    }

    /** A dedicated action endpoint, not folded into the generic update - roomId needs real null-clear semantics. See phase 28's controlled-substance-schedule precedent. */
    @PostMapping("/api/inventory/assets/{id}/assign-room")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Asset assignRoom(@PathVariable UUID id, @RequestBody AssignRoomRequest request) {
        UUID tenantId = TenantContext.require();
        Asset asset = requireOwnedAsset(id, tenantId);
        if (request.roomId() != null) {
            roomRepository.findByIdAndTenantId(request.roomId(), tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Room not found: " + request.roomId()));
        }
        asset.setAssignedRoomId(request.roomId());
        return assetRepository.save(asset);
    }

    @GetMapping("/api/inventory/assets/{id}/maintenance-records")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<AssetMaintenanceRecord> maintenanceRecords(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedAsset(id, tenantId);
        return assetMaintenanceRecordRepository.findAllByAssetIdAndTenantId(id, tenantId);
    }

    @PostMapping("/api/inventory/assets/{id}/maintenance-records")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public AssetMaintenanceRecord recordMaintenance(
            @PathVariable UUID id, @Valid @RequestBody CreateAssetMaintenanceRecordRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedAsset(id, tenantId);
        AssetMaintenanceRecord record = new AssetMaintenanceRecord();
        record.setTenantId(tenantId);
        record.setAssetId(id);
        record.setDescription(request.description());
        record.setNotes(request.notes());
        record.setPerformedBy(currentUserService.resolveInternalUserId(jwt));
        return assetMaintenanceRecordRepository.save(record);
    }

    private Asset requireOwnedAsset(UUID id, UUID tenantId) {
        return assetRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Asset not found: " + id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
