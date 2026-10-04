package com.clinicops.messaging;

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
 * Staff's own side of the shared clinic inbox - provider+clinic_admin
 * only, the user's own pinned answer (no front_desk access - message
 * content can be clinical in nature, same reasoning Encounter's own
 * front_desk exclusion already uses). No ownership check beyond
 * tenant - any provider/clinic_admin at the clinic can see and reply to
 * any patient's thread, by design (that's the whole point of a *shared*
 * inbox, not a per-provider one).
 */
@RestController
public class StaffMessagingController {

    private final PatientMessagingService patientMessagingService;
    private final PhiAccessAuditService phiAccessAuditService;

    public StaffMessagingController(PatientMessagingService patientMessagingService, PhiAccessAuditService phiAccessAuditService) {
        this.patientMessagingService = patientMessagingService;
        this.phiAccessAuditService = phiAccessAuditService;
    }

    @GetMapping("/api/clinic/message-inbox")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<MessageInboxEntry> inbox(@AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        phiAccessAuditService.logRead(tenantId, jwt, "patient_message_inbox", null, null, "/api/clinic/message-inbox");
        return patientMessagingService.inbox(tenantId);
    }

    @GetMapping("/api/patients/{patientId}/messages")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<PatientMessage> thread(@PathVariable UUID patientId, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        List<PatientMessage> messages = patientMessagingService.threadForStaff(patientId, tenantId);
        phiAccessAuditService.logRead(tenantId, jwt, "patient_message_list", null, patientId, "/api/patients/{patientId}/messages");
        return messages;
    }

    @PostMapping("/api/patients/{patientId}/messages")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public PatientMessage reply(@PathVariable UUID patientId, @Valid @RequestBody SendStaffMessageRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        PatientMessage message = patientMessagingService.replyAsStaff(patientId, tenantId, request.body(), jwt);
        phiAccessAuditService.logWrite(tenantId, jwt, "patient_message", message.getId(), patientId, "/api/patients/{patientId}/messages");
        return message;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
