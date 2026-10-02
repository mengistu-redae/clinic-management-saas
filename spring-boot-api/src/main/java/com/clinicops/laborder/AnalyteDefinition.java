package com.clinicops.laborder;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One expected analyte for a test_code (keyed off lab_test_rates.test_code,
 * no JPA relation - same "own table, explicit repository lookups"
 * convention every entity in this package already follows). A
 * single-analyte test (e.g. "Glucose") is just the degenerate one-row
 * case - a panel (e.g. "CBC") gets one row per component (WBC/RBC/Hgb/...).
 *
 * One range per test+analyte, no age/sex banding (the recommended option
 * from this module's own pinned fork, 2026-10-02) - normalRangeLow/High
 * for a numeric analyte (drives AnalyteResult's own auto-flag), or
 * normalRangeText for a qualitative one (e.g. "Negative") with no
 * auto-flagging computed for it.
 */
@Entity
@Table(name = "analyte_definitions")
@Getter
@Setter
public class AnalyteDefinition extends BaseTenantEntity {

    @Column(name = "test_code", nullable = false)
    private String testCode;

    @Column(name = "analyte_name", nullable = false)
    private String analyteName;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    private String unit;

    @Column(name = "normal_range_low")
    private BigDecimal normalRangeLow;

    @Column(name = "normal_range_high")
    private BigDecimal normalRangeHigh;

    @Column(name = "normal_range_text")
    private String normalRangeText;
}
