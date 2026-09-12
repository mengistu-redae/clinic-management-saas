package com.clinicops.scheduling;

import java.time.Instant;
import java.util.UUID;

/** A narrow, public-facing read shape for browsing availability - no tenant/internal detail beyond what a booker needs to pick a slot. */
public record AvailableSlotView(UUID id, UUID providerId, Instant startTime, Instant endTime) {

    static AvailableSlotView from(Slot slot) {
        return new AvailableSlotView(slot.getId(), slot.getProviderId(), slot.getStartTime(), slot.getEndTime());
    }
}
