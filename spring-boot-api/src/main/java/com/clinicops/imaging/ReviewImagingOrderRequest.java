package com.clinicops.imaging;

/** The one combined action that both writes the report and signs off on it - see ImagingOrder's own javadoc for why findings entry and review aren't split into two actions the way lab splits result-entry from review. */
public record ReviewImagingOrderRequest(
        String findings,
        String impression,
        boolean criticalFinding
) {
}
