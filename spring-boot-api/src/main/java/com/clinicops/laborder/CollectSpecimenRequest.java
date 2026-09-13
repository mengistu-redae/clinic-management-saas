package com.clinicops.laborder;

/** presentedIdNumber is optional - an absent/blank value means nothing was presented to check. */
public record CollectSpecimenRequest(String presentedIdNumber) {
}
