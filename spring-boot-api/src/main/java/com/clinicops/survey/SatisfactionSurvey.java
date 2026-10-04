package com.clinicops.survey;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per appointment ({@code appointmentId} unique at the DB level,
 * same "exactly one per visit" shape as Vitals.appointmentId) - auto
 * -created by CheckInService.checkOut the moment an appointment reaches
 * checked_out, with rating/comment/submittedAt all null until the patient
 * actually fills it in. submittedAt null means "pending"; non-null means
 * "submitted and immutable" - SatisfactionSurveyService rejects a second
 * submission rather than silently overwriting feedback already on record.
 */
@Entity
@Table(name = "satisfaction_surveys")
@Getter
@Setter
public class SatisfactionSurvey extends BaseTenantEntity {

    @Column(name = "appointment_id", nullable = false, unique = true)
    private UUID appointmentId;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "comment")
    private String comment;

    @Column(name = "submitted_at")
    private Instant submittedAt;
}
