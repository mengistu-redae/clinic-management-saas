package com.clinicops.survey;

import java.math.BigDecimal;
import java.util.List;

/** clinic_admin's own aggregate view - no per-provider breakdown, the user's own pinned scope for this phase. */
public record SatisfactionSurveySummary(
        BigDecimal averageRating,
        long totalSubmitted,
        List<SatisfactionSurveyFeedbackEntry> recentFeedback) {
}
