package com.clinicops.survey;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointment.AppointmentWithSlotView;
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

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Patient's own side - ownership resolved via customer_user_id, never
 * TenantContext (patient JWTs carry no org claim), same pattern
 * VisitSummaryController's own patient endpoint already established. Not
 * PHI-audited - matches PhiAccessAuditService's documented staff-initiated
 * -access-only scope; feedback data isn't clinical PHI either way.
 */
@RestController
public class PatientSatisfactionSurveyController {

    private final AppointmentRepository appointmentRepository;
    private final SatisfactionSurveyService satisfactionSurveyService;
    private final CurrentUserService currentUserService;

    public PatientSatisfactionSurveyController(
            AppointmentRepository appointmentRepository,
            SatisfactionSurveyService satisfactionSurveyService,
            CurrentUserService currentUserService) {
        this.appointmentRepository = appointmentRepository;
        this.satisfactionSurveyService = satisfactionSurveyService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/my-appointments/{id}/survey")
    @PreAuthorize("hasRole('PATIENT')")
    public SatisfactionSurvey mySurvey(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        AppointmentWithSlotView appointment = resolveOwned(id, jwt);
        return satisfactionSurveyService.get(id, appointment.getTenantId());
    }

    @PostMapping("/api/my-appointments/{id}/survey")
    @PreAuthorize("hasRole('PATIENT')")
    public SatisfactionSurvey submit(@PathVariable UUID id, @Valid @RequestBody SubmitSatisfactionSurveyRequest request, @AuthenticationPrincipal Jwt jwt) {
        AppointmentWithSlotView appointment = resolveOwned(id, jwt);
        return satisfactionSurveyService.submit(id, appointment.getTenantId(), request);
    }

    private AppointmentWithSlotView resolveOwned(UUID appointmentId, Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return appointmentRepository.findByIdAndCustomerUserIdWithSlot(appointmentId, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
