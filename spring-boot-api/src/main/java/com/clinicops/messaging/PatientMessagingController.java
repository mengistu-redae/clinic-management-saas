package com.clinicops.messaging;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Patient's own side of the shared clinic inbox - a patient token carries no org claim, so clinicId is explicit on every call, never resolved via TenantContext. */
@RestController
public class PatientMessagingController {

    private final PatientMessagingService patientMessagingService;

    public PatientMessagingController(PatientMessagingService patientMessagingService) {
        this.patientMessagingService = patientMessagingService;
    }

    @PostMapping("/api/my-messages")
    @PreAuthorize("hasRole('PATIENT')")
    public PatientMessage send(@Valid @RequestBody CreatePatientMessageRequest request, @AuthenticationPrincipal Jwt jwt) {
        return patientMessagingService.sendAsPatient(request.clinicId(), request.body(), jwt);
    }

    @GetMapping("/api/my-messages")
    @PreAuthorize("hasRole('PATIENT')")
    public List<PatientMessage> myMessages(@RequestParam UUID clinicId, @AuthenticationPrincipal Jwt jwt) {
        return patientMessagingService.myMessages(clinicId, jwt);
    }
}
