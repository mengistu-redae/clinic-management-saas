package com.clinicops.survey;

import java.math.BigDecimal;

/** Projection backing SatisfactionSurveyRepository.statsForTenant - averageRating is null when totalSubmitted is 0 (AVG of zero rows), not zero. */
public interface SatisfactionSurveyStats {
    BigDecimal getAverageRating();
    long getTotalSubmitted();
}
