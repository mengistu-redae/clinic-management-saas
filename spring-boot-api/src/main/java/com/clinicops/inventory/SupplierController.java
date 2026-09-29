package com.clinicops.inventory;

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

/** Plain soft-deactivate CRUD, same shape as RoomController. Same phase-29 role gate as the rest of this package. */
@RestController
public class SupplierController {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final SupplierRepository supplierRepository;

    public SupplierController(SupplierRepository supplierRepository) {
        this.supplierRepository = supplierRepository;
    }

    @GetMapping("/api/inventory/suppliers")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public List<Supplier> suppliers(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? supplierRepository.findAllByTenantId(tenantId)
                : supplierRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/inventory/suppliers/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Supplier supplier(@PathVariable UUID id) {
        return requireOwnedSupplier(id, TenantContext.require());
    }

    @PostMapping("/api/inventory/suppliers")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Supplier createSupplier(@Valid @RequestBody CreateSupplierRequest request) {
        Supplier supplier = new Supplier();
        supplier.setTenantId(TenantContext.require());
        supplier.setName(request.name());
        supplier.setContactName(request.contactName());
        supplier.setPhone(request.phone());
        supplier.setEmail(request.email());
        return supplierRepository.save(supplier);
    }

    @PostMapping("/api/inventory/suppliers/{id}/update")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Supplier updateSupplier(@PathVariable UUID id, @RequestBody UpdateSupplierRequest request) {
        Supplier supplier = requireOwnedSupplier(id, TenantContext.require());
        if (request.name() != null) {
            supplier.setName(request.name());
        }
        if (request.contactName() != null) {
            supplier.setContactName(request.contactName());
        }
        if (request.phone() != null) {
            supplier.setPhone(request.phone());
        }
        if (request.email() != null) {
            supplier.setEmail(request.email());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            supplier.setStatus(request.status());
        }
        return supplierRepository.save(supplier);
    }

    private Supplier requireOwnedSupplier(UUID id, UUID tenantId) {
        return supplierRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Supplier not found: " + id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
