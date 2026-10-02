package com.clinicops.laborder;

import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.tenant.TenantContext;
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
 * Structured per-analyte result entry (lab module L2) - read access matches
 * LabOrderController's own widened gate (provider+clinic_admin+
 * lab_technician); write is lab_technician+clinic_admin only, the same
 * split LabOrderStatusController's own re-gated /result endpoint uses
 * (phase L1), since entering a result is entering a result regardless of
 * which of the two paths does it.
 */
@RestController
public class AnalyteResultController {

    private final AnalyteResultService analyteResultService;
    private final LabOrderRepository labOrderRepository;
    private final PhiAccessAuditService phiAccessAuditService;

    public AnalyteResultController(
            AnalyteResultService analyteResultService, LabOrderRepository labOrderRepository, PhiAccessAuditService phiAccessAuditService) {
        this.analyteResultService = analyteResultService;
        this.labOrderRepository = labOrderRepository;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/lab-orders/{orderId}/tests/{testId}/analyte-results")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN', 'LAB_TECHNICIAN')")
    public List<AnalyteResult> analyteResults(@PathVariable UUID orderId, @PathVariable UUID testId, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        List<AnalyteResult> results = analyteResultService.listForTest(orderId, testId, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "lab_order_analyte_results", testId, patientIdFor(orderId, tenantId), "/api/lab-orders/{orderId}/tests/{testId}/analyte-results");
        return results;
    }

    @PostMapping("/api/lab-orders/{orderId}/tests/{testId}/analyte-results")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public List<AnalyteResult> enterAnalyteResults(
            @PathVariable UUID orderId, @PathVariable UUID testId,
            @Valid @RequestBody EnterAnalyteResultsRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        List<AnalyteResult> results = analyteResultService.enterResults(orderId, testId, tenantId, request.results());
        phiAccessAuditService.logWrite(tenantId, jwt, "lab_order_analyte_results", testId, patientIdFor(orderId, tenantId), "/api/lab-orders/{orderId}/tests/{testId}/analyte-results");
        return results;
    }

    private UUID patientIdFor(UUID orderId, UUID tenantId) {
        return labOrderRepository.findByIdAndTenantId(orderId, tenantId).map(LabOrder::getPatientId).orElse(null);
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
