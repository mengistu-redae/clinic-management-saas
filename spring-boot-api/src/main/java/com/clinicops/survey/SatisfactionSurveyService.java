package com.clinicops.survey;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - same "not a contended
 * resource" reasoning VitalsService/EncounterService already give.
 */
@Service
public class SatisfactionSurveyService {

    private final SatisfactionSurveyRepository satisfactionSurveyRepository;

    public SatisfactionSurveyService(SatisfactionSurveyRepository satisfactionSurveyRepository) {
        this.satisfactionSurveyRepository = satisfactionSurveyRepository;
    }

    /**
     * Called once from CheckInService.checkOut, every time that method
     * itself runs (including its own idempotent re-calls) - a no-op when a
     * row already exists for this appointment, so calling it redundantly is
     * always safe. Never called directly from a controller.
     */
    @Transactional
    public void createPendingForAppointment(UUID appointmentId, UUID tenantId) {
        if (satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId).isPresent()) {
            return;
        }
        SatisfactionSurvey survey = new SatisfactionSurvey();
        survey.setTenantId(tenantId);
        survey.setAppointmentId(appointmentId);
        satisfactionSurveyRepository.save(survey);
    }

    @Transactional(readOnly = true)
    public SatisfactionSurvey get(UUID appointmentId, UUID tenantId) {
        return satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No survey available yet for appointment: " + appointmentId));
    }

    /** Genuinely one-shot - once submittedAt is set, a second call 409s rather than silently overwriting feedback already on record. */
    @Transactional
    public SatisfactionSurvey submit(UUID appointmentId, UUID tenantId, SubmitSatisfactionSurveyRequest request) {
        SatisfactionSurvey survey = get(appointmentId, tenantId);
        if (survey.getSubmittedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This survey has already been submitted");
        }
        survey.setRating(request.rating());
        survey.setComment(request.comment());
        survey.setSubmittedAt(Instant.now());
        return satisfactionSurveyRepository.save(survey);
    }
}
