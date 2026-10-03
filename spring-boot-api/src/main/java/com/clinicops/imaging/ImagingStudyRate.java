package com.clinicops.imaging;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One row per (tenant, studyCode) - mirrors LabTestRate exactly, including
 * its "missing rate blocks order creation entirely" convention (see
 * NoImagingRateConfiguredException), the deliberate opposite of fee_policies'
 * own "missing = zero" fallback.
 */
@Entity
@Table(name = "imaging_study_rates")
@Getter
@Setter
public class ImagingStudyRate extends BaseTenantEntity {

    @Column(name = "study_code", nullable = false)
    private String studyCode;

    @Column(name = "study_name", nullable = false)
    private String studyName;

    /** xray, ultrasound, ct, mri, other. */
    @Column(nullable = false)
    private String modality;

    @Column(name = "base_charge", nullable = false)
    private BigDecimal baseCharge;
}
