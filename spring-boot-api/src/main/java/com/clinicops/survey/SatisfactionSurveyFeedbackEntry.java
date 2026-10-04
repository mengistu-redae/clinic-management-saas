package com.clinicops.survey;

import java.time.Instant;

/** Projection backing SatisfactionSurveyRepository.recentFeedbackForTenant - patientName is resolved in SQL, never a second lookup. */
public interface SatisfactionSurveyFeedbackEntry {
    Integer getRating();
    String getComment();
    Instant getSubmittedAt();
    String getPatientName();
}
