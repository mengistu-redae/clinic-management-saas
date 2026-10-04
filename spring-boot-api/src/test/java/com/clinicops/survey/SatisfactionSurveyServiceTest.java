package com.clinicops.survey;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SatisfactionSurveyServiceTest {

    private final SatisfactionSurveyRepository satisfactionSurveyRepository = mock(SatisfactionSurveyRepository.class);
    private final SatisfactionSurveyService service = new SatisfactionSurveyService(satisfactionSurveyRepository);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID appointmentId = UUID.randomUUID();

    @Test
    void createPendingForAppointmentSavesANewRowWhenNoneExists() {
        when(satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());

        service.createPendingForAppointment(appointmentId, tenantId);

        verify(satisfactionSurveyRepository).save(any(SatisfactionSurvey.class));
    }

    @Test
    void createPendingForAppointmentIsANoOpWhenARowAlreadyExists() {
        when(satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId))
                .thenReturn(Optional.of(new SatisfactionSurvey()));

        service.createPendingForAppointment(appointmentId, tenantId);

        verify(satisfactionSurveyRepository, never()).save(any());
    }

    @Test
    void getThrowsWhenNoSurveyExistsYet() {
        when(satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(appointmentId, tenantId))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void submitFillsInRatingCommentAndSubmittedAtWhenPending() {
        SatisfactionSurvey pending = new SatisfactionSurvey();
        pending.setAppointmentId(appointmentId);
        pending.setTenantId(tenantId);
        when(satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(pending));
        when(satisfactionSurveyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SatisfactionSurvey result = service.submit(appointmentId, tenantId, new SubmitSatisfactionSurveyRequest(5, "Great visit"));

        assertThat(result.getRating()).isEqualTo(5);
        assertThat(result.getComment()).isEqualTo("Great visit");
        assertThat(result.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitRejectsASecondSubmission() {
        SatisfactionSurvey alreadySubmitted = new SatisfactionSurvey();
        alreadySubmitted.setAppointmentId(appointmentId);
        alreadySubmitted.setTenantId(tenantId);
        alreadySubmitted.setRating(4);
        alreadySubmitted.setSubmittedAt(Instant.now());
        when(satisfactionSurveyRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(alreadySubmitted));

        assertThatThrownBy(() -> service.submit(appointmentId, tenantId, new SubmitSatisfactionSurveyRequest(1, "changed my mind")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already been submitted");

        verify(satisfactionSurveyRepository, never()).save(any());
    }
}
