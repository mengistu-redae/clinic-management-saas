package com.clinicops.laborder;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/** One line item on a LabOrder. testCode/price are null on a patient-initiated request until staff confirm-and-order fills them in. */
@Entity
@Table(name = "lab_order_tests")
@Getter
@Setter
public class LabOrderTest extends BaseTenantEntity {

    @Column(name = "lab_order_id", nullable = false)
    private UUID labOrderId;

    @Column(name = "test_code")
    private String testCode;

    @Column(name = "test_name", nullable = false)
    private String testName;

    @Column(name = "specimen_type")
    private String specimenType;

    private String notes;

    /** Snapshotted at pricing time - null until priced. */
    private BigDecimal price;

    @Column(name = "result_value")
    private String resultValue;

    @Column(name = "result_unit")
    private String resultUnit;

    @Column(name = "reference_range")
    private String referenceRange;

    @Column(name = "abnormal_flag")
    private Boolean abnormalFlag;

    /** Set once SpecimenService derives a Specimen for this test's own specimenType - null if specimenType was never given. */
    @Column(name = "specimen_id")
    private UUID specimenId;
}
