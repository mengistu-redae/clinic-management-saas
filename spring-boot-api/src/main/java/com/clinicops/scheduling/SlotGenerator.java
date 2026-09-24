package com.clinicops.scheduling;

import com.clinicops.provider.ProviderWorkingHours;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a provider's working-hours windows for one calendar day into
 * whole-slot (start, end) boundaries of a given duration - the direct
 * analog of the reference bus-ticketing-saas project's SeatLayoutGenerator
 * (which turns a bus's capacity/layout into seat numbers), adapted for a
 * rolling calendar instead of a one-shot-at-creation generation.
 *
 * A window that doesn't divide evenly by the duration drops its leftover
 * remainder rather than generating a short final slot (e.g. a 25-minute
 * tail on a 90-minute window with 30-minute slots is simply unused).
 * Working hours are interpreted in the given {@code zone} (phase 18 -
 * ClinicSettingsService.resolveTimezone(tenantId), UTC platform-wide
 * default until a clinic sets its own) - resolved via {@code atZone(...)}
 * rather than a plain {@code ZoneOffset}, so a region-based zone's own DST
 * rules for that specific date are honored correctly, not just a fixed
 * offset.
 */
final class SlotGenerator {

    private SlotGenerator() {
    }

    /** clinics/provider_working_hours use 0=Sunday.."6"=Saturday (see its column comment); java.time.DayOfWeek is 1=Monday..7=Sunday. */
    static short dayOfWeek(LocalDate date) {
        return (short) (date.getDayOfWeek().getValue() % 7);
    }

    static List<Instant[]> generateForDay(
            LocalDate date, List<ProviderWorkingHours> windowsForDay, int durationMinutes, Instant notBefore, ZoneId zone) {
        List<Instant[]> slots = new ArrayList<>();
        Duration duration = Duration.ofMinutes(durationMinutes);

        for (ProviderWorkingHours window : windowsForDay) {
            Instant windowStart = date.atTime(window.getStartTime()).atZone(zone).toInstant();
            Instant windowEnd = date.atTime(window.getEndTime()).atZone(zone).toInstant();

            Instant cursor = windowStart;
            while (!cursor.plus(duration).isAfter(windowEnd)) {
                Instant slotEnd = cursor.plus(duration);
                if (!cursor.isBefore(notBefore)) {
                    slots.add(new Instant[] {cursor, slotEnd});
                }
                cursor = slotEnd;
            }
        }
        return slots;
    }
}
