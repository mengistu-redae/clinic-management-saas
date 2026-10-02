package com.clinicops.laborder;

import com.clinicops.phiaudit.PhiAccessAuditService;
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
 * The new fine-grained path a lab_technician uses directly, alongside (not
 * instead of) LabOrderStatusController's own order-level actions - see
 * Specimen/SpecimenService's own javadoc. Read access is the same
 * provider+clinic_admin+lab_technician set LabOrderController itself uses;
 * every write action here is lab_technician+clinic_admin only, matching the
 * pinned role split (lab_technician owns collection/processing, provider
 * keeps the order-level "review" sign-off and never touches specimens
 * directly).
 */
@RestController
public class SpecimenController {

    private final SpecimenService specimenService;
    private final LabOrderRepository labOrderRepository;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public SpecimenController(
            SpecimenService specimenService,
            LabOrderRepository labOrderRepository,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.specimenService = specimenService;
        this.labOrderRepository = labOrderRepository;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/lab-orders/{id}/specimens")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'PROVIDER', 'CLINIC_ADMIN')")
    public List<Specimen> specimens(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        var order = requireOrder(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "lab_order_specimens", id, order.getPatientId(), "/api/lab-orders/{id}/specimens");
        return specimenService.listForOrder(id);
    }

    @PostMapping("/api/specimens/{id}/collect")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public Specimen collect(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID collectedBy = currentUserService.resolveInternalUserId(jwt);
        Specimen specimen = specimenService.collect(id, tenantId, collectedBy);
        var order = requireOrder(specimen.getLabOrderId(), tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_specimen", id, order.getPatientId(), "/api/specimens/{id}/collect");
        return specimen;
    }

    @PostMapping("/api/specimens/{id}/mark-in-transit")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public Specimen markInTransit(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Specimen specimen = specimenService.markInTransit(id, tenantId);
        var order = requireOrder(specimen.getLabOrderId(), tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_specimen", id, order.getPatientId(), "/api/specimens/{id}/mark-in-transit");
        return specimen;
    }

    @PostMapping("/api/specimens/{id}/receive")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public Specimen receive(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Specimen specimen = specimenService.receive(id, tenantId);
        var order = requireOrder(specimen.getLabOrderId(), tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_specimen", id, order.getPatientId(), "/api/specimens/{id}/receive");
        return specimen;
    }

    @PostMapping("/api/specimens/{id}/complete")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public Specimen complete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Specimen specimen = specimenService.complete(id, tenantId);
        var order = requireOrder(specimen.getLabOrderId(), tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_specimen", id, order.getPatientId(), "/api/specimens/{id}/complete");
        return specimen;
    }

    @PostMapping("/api/specimens/{id}/reject")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public Specimen reject(@PathVariable UUID id, @Valid @RequestBody RejectSpecimenRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Specimen specimen = specimenService.reject(id, tenantId, request.reason());
        var order = requireOrder(specimen.getLabOrderId(), tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_specimen", id, order.getPatientId(), "/api/specimens/{id}/reject");
        return specimen;
    }

    /** L5 - routes a specimen to an outside lab; results still come back through the existing structured per-analyte path (L2), not a new one. */
    @PostMapping("/api/specimens/{id}/send-to-reference-lab")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public Specimen sendToReferenceLab(
            @PathVariable UUID id, @Valid @RequestBody SendToReferenceLabRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        Specimen specimen = specimenService.sendToReferenceLab(id, tenantId, request);
        var order = requireOrder(specimen.getLabOrderId(), tenantId);
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_specimen", id, order.getPatientId(), "/api/specimens/{id}/send-to-reference-lab");
        return specimen;
    }

    private LabOrder requireOrder(UUID id, UUID tenantId) {
        return labOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + id));
    }

    @ExceptionHandler(InvalidLabOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidLabOrderStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
