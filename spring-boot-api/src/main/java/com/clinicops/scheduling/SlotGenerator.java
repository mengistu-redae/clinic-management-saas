package com.clinicops.scheduling;

import com.clinicops.provider.ProviderWorkingHours;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * Working hours are interpreted in UTC - this app has no per-clinic
 * timezone concept yet (see CLAUDE.md's known gaps); a real deployment
 * spanning time zones would need one.
 */
final class SlotGenerator {

    private SlotGenerator() {
    }

    /** clinics/provider_working_hours use 0=Sunday.."6"=Saturday (see its column comment); java.time.DayOfWeek is 1=Monday..7=Sunday. */
    static short dayOfWeek(LocalDate date) {
        return (short) (date.getDayOfWeek().getValue() % 7);
    }

    static List<Instant[]> generateForDay(
            LocalDate date, List<ProviderWorkingHours> windowsForDay, int durationMinutes, Instant notBefore) {
        List<Instant[]> slots = new ArrayList<>();
        Duration duration = Duration.ofMinutes(durationMinutes);

        for (ProviderWorkingHours window : windowsForDay) {
            Instant windowStart = date.atTime(window.getStartTime()).toInstant(ZoneOffset.UTC);
            Instant windowEnd = date.atTime(window.getEndTime()).toInstant(ZoneOffset.UTC);

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
