package com.clinicops.survey;

import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * clinic_admin only, aggregate-only (the user's own pinned scope for this
 * phase) - average rating + a recent-comments feed, no per-provider
 * breakdown and no ownership check beyond tenant (every submitted survey
 * at this clinic feeds the same one summary).
 */
@RestController
public class ClinicSatisfactionSurveyController {

    private static final int RECENT_FEEDBACK_LIMIT = 20;

    private final SatisfactionSurveyRepository satisfactionSurveyRepository;

    public ClinicSatisfactionSurveyController(SatisfactionSurveyRepository satisfactionSurveyRepository) {
        this.satisfactionSurveyRepository = satisfactionSurveyRepository;
    }

    @GetMapping("/api/clinic/satisfaction-surveys/summary")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public SatisfactionSurveySummary summary() {
        UUID tenantId = TenantContext.require();
        SatisfactionSurveyStats stats = satisfactionSurveyRepository.statsForTenant(tenantId);
        return new SatisfactionSurveySummary(
                stats.getAverageRating(),
                stats.getTotalSubmitted(),
                satisfactionSurveyRepository.recentFeedbackForTenant(tenantId, RECENT_FEEDBACK_LIMIT));
    }
}
