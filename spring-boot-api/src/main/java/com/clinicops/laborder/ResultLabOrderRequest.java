package com.clinicops.laborder;

import jakarta.validation.Valid;

import java.util.List;

/**
 * results may now be empty (not just non-empty) - lab module L2 added a
 * parallel structured per-analyte result path (AnalyteResultController)
 * that doesn't itself flip the owning LabOrder's own status, so this
 * endpoint needs to stay callable purely to mark an order resulted once
 * every real value was entered there instead, with no flat-style inputs
 * left to provide here.
 */
public record ResultLabOrderRequest(List<@Valid TestResultInput> results) {
    public ResultLabOrderRequest {
        if (results == null) {
            results = List.of();
        }
    }
}
