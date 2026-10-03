package com.clinicops.imaging;

import java.time.Instant;

/** scheduledAt is optional - a walk-in study can skip formal scheduling and go straight to `start`. */
public record ScheduleImagingOrderRequest(
        Instant scheduledAt
) {
}
