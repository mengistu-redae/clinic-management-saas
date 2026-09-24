package com.clinicops.scheduling;

import com.clinicops.provider.ProviderWorkingHours;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlotGeneratorTest {

    @Test
    void dayOfWeekMapsSundayToZeroAndSaturdayToSix() {
        // 2026-09-13 is a Sunday, 2026-09-19 is the following Saturday.
        assertThat(SlotGenerator.dayOfWeek(LocalDate.of(2026, 9, 13))).isEqualTo((short) 0);
        assertThat(SlotGenerator.dayOfWeek(LocalDate.of(2026, 9, 14))).isEqualTo((short) 1); // Monday
        assertThat(SlotGenerator.dayOfWeek(LocalDate.of(2026, 9, 19))).isEqualTo((short) 6);
    }

    @Test
    void slicesAWorkingHoursWindowIntoWholeSlotsOfTheGivenDuration() {
        ProviderWorkingHours window = workingHours(LocalTime.of(9, 0), LocalTime.of(10, 0));
        LocalDate date = LocalDate.of(2026, 9, 14);

        List<Instant[]> slots = SlotGenerator.generateForDay(date, List.of(window), 30, Instant.EPOCH, ZoneOffset.UTC);

        assertThat(slots).hasSize(2);
        assertThat(slots.get(0)[0]).isEqualTo(date.atTime(9, 0).toInstant(ZoneOffset.UTC));
        assertThat(slots.get(0)[1]).isEqualTo(date.atTime(9, 30).toInstant(ZoneOffset.UTC));
        assertThat(slots.get(1)[0]).isEqualTo(date.atTime(9, 30).toInstant(ZoneOffset.UTC));
        assertThat(slots.get(1)[1]).isEqualTo(date.atTime(10, 0).toInstant(ZoneOffset.UTC));
    }

    @Test
    void dropsALeftoverRemainderRatherThanGeneratingAShortFinalSlot() {
        // 9:00-9:50, 30-minute slots: one whole slot fits (9:00-9:30), the
        // remaining 20 minutes (9:30-9:50) is unused, not a short slot.
        ProviderWorkingHours window = workingHours(LocalTime.of(9, 0), LocalTime.of(9, 50));

        List<Instant[]> slots = SlotGenerator.generateForDay(LocalDate.of(2026, 9, 14), List.of(window), 30, Instant.EPOCH, ZoneOffset.UTC);

        assertThat(slots).hasSize(1);
    }

    @Test
    void excludesSlotsThatStartBeforeNotBefore() {
        ProviderWorkingHours window = workingHours(LocalTime.of(9, 0), LocalTime.of(10, 0));
        LocalDate date = LocalDate.of(2026, 9, 14);
        Instant notBefore = date.atTime(9, 30).toInstant(ZoneOffset.UTC);

        List<Instant[]> slots = SlotGenerator.generateForDay(date, List.of(window), 30, notBefore, ZoneOffset.UTC);

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0)[0]).isEqualTo(notBefore);
    }

    @Test
    void emptyWorkingHoursProducesNoSlots() {
        assertThat(SlotGenerator.generateForDay(LocalDate.of(2026, 9, 14), List.of(), 30, Instant.EPOCH, ZoneOffset.UTC)).isEmpty();
    }

    @Test
    void multipleWindowsInOneDayEachProduceTheirOwnSlots() {
        // A lunch-break split shift: 9-12 and 13-15.
        ProviderWorkingHours morning = workingHours(LocalTime.of(9, 0), LocalTime.of(12, 0));
        ProviderWorkingHours afternoon = workingHours(LocalTime.of(13, 0), LocalTime.of(15, 0));

        List<Instant[]> slots = SlotGenerator.generateForDay(
                LocalDate.of(2026, 9, 14), List.of(morning, afternoon), 60, Instant.EPOCH, ZoneOffset.UTC);

        assertThat(slots).hasSize(5); // 3 in the morning, 2 in the afternoon
    }

    /**
     * Phase 18 - a non-UTC zone case, specifically one where local midnight
     * isn't UTC midnight (Africa/Addis_Ababa is UTC+3 year-round, no DST),
     * the case most likely to surface an off-by-one in day-boundary math. A
     * 09:00-10:00 local window on 2026-09-14 in Addis Ababa is 06:00-07:00
     * UTC that same calendar day - if generateForDay silently fell back to
     * treating the zone as UTC, these instants would be wrong by exactly
     * the 3-hour offset.
     */
    @Test
    void interpretsWorkingHoursInTheGivenNonUtcZone() {
        ZoneId addisAbaba = ZoneId.of("Africa/Addis_Ababa");
        ProviderWorkingHours window = workingHours(LocalTime.of(9, 0), LocalTime.of(10, 0));
        LocalDate date = LocalDate.of(2026, 9, 14);

        List<Instant[]> slots = SlotGenerator.generateForDay(date, List.of(window), 30, Instant.EPOCH, addisAbaba);

        assertThat(slots).hasSize(2);
        assertThat(slots.get(0)[0]).isEqualTo(date.atTime(9, 0).atZone(addisAbaba).toInstant());
        assertThat(slots.get(0)[0]).isEqualTo(date.atTime(6, 0).toInstant(ZoneOffset.UTC));
    }

    private ProviderWorkingHours workingHours(LocalTime start, LocalTime end) {
        ProviderWorkingHours hours = new ProviderWorkingHours();
        hours.setStartTime(start);
        hours.setEndTime(end);
        return hours;
    }
}
