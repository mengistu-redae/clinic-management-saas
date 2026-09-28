package com.clinicops.visitsummary;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.AppointmentWithSlotView;
import com.clinicops.phiaudit.PhiAccessAuditService;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Two endpoints, both delegating to the same {@link VisitSummaryPdfService
 * #render(UUID, UUID)} after resolving ownership differently - this app's
 * first patient-facing document-download endpoint. No status gate on
 * either (matches Invoice's own "no status gate" precedent) - an
 * incomplete visit just renders a mostly-empty PDF, not an error.
 */
@RestController
public class VisitSummaryController {

    private final AppointmentRepository appointmentRepository;
    private final VisitSummaryPdfService visitSummaryPdfService;
    private final CurrentUserService currentUserService;
    private final PhiAccessAuditService phiAccessAuditService;

    public VisitSummaryController(
            AppointmentRepository appointmentRepository,
            VisitSummaryPdfService visitSummaryPdfService,
            CurrentUserService currentUserService,
            PhiAccessAuditService phiAccessAuditService) {
        this.appointmentRepository = appointmentRepository;
        this.visitSummaryPdfService = visitSummaryPdfService;
        this.currentUserService = currentUserService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    /** Staff - same 3-role read gate AppointmentInvoiceController's own PDF endpoint uses. PHI-audited (a new aggregate view over clinical data). */
    @GetMapping("/api/appointments/{id}/visit-summary/pdf")
    @PreAuthorize("hasAnyRole('PROVIDER', 'FRONT_DESK', 'CLINIC_ADMIN')")
    public ResponseEntity<byte[]> visitSummaryPdf(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        var appointment = appointmentRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + id));
        byte[] pdf = visitSummaryPdfService.render(id, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "visit_summary", id, appointment.getPatientId(), "/api/appointments/{id}/visit-summary/pdf");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf);
    }

    /**
     * Patient - resolved via customer_user_id, never TenantContext (patient
     * JWTs carry no org claim). Not PHI-audited - matches PhiAccessAuditService's
     * own documented scope (staff-initiated access only, not a patient
     * viewing their own record), same as PatientLabRequestController's
     * own patient-facing endpoints.
     */
    @GetMapping("/api/my-appointments/{id}/visit-summary/pdf")
    @PreAuthorize("hasRole('PATIENT')")
    public ResponseEntity<byte[]> myVisitSummaryPdf(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        AppointmentWithSlotView appointment = appointmentRepository.findByIdAndCustomerUserIdWithSlot(id, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + id));
        byte[] pdf = visitSummaryPdfService.render(id, appointment.getTenantId());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(pdf);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
