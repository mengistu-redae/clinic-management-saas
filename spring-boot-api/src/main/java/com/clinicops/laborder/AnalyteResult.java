package com.clinicops.laborder;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One structured result value for one analyte on one LabOrderTest -
 * additive alongside that same LabOrderTest's own flat result_value/
 * result_unit/reference_range/abnormal_flag columns, which stay exactly as
 * they are (see AnalyteResultService's own javadoc for how the two paths
 * coexist). analyteName/unit/referenceRangeDisplay are snapshotted from
 * AnalyteDefinition at entry time - analyteDefinitionId can be null (an
 * ad-hoc analyte with no catalog entry is still enterable, just never
 * flagged).
 */
@Entity
@Table(name = "analyte_results")
@Getter
@Setter
public class AnalyteResult extends BaseTenantEntity {

    @Column(name = "lab_order_test_id", nullable = false)
    private UUID labOrderTestId;

    @Column(name = "analyte_definition_id")
    private UUID analyteDefinitionId;

    @Column(name = "analyte_name", nullable = false)
    private String analyteName;

    @Column(nullable = false)
    private String value;

    private String unit;

    @Column(name = "reference_range_display")
    private String referenceRangeDisplay;

    /** normal, abnormal, critical, or unflagged (L3 - genuinely 3-way now that AnalyteDefinition carries a critical range too). */
    @Column(nullable = false)
    private String flag = "unflagged";

    /** Meaningless (always null) unless flag == "critical" - who acknowledged the alert, and when. */
    @Column(name = "critical_acknowledged_at")
    private Instant criticalAcknowledgedAt;

    @Column(name = "critical_acknowledged_by")
    private UUID criticalAcknowledgedBy;
}
