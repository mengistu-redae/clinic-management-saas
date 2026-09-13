package com.clinicops.laborder;

import java.util.List;

/** GET/POST read-shape - LabOrderTest has no JPA relation back to LabOrder (plain FK column), so the two are fetched separately and composed here. */
public record LabOrderWithTests(LabOrder order, List<LabOrderTest> tests) {
}
