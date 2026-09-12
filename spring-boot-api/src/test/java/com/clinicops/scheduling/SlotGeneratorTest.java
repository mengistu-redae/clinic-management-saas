package com.clinicops.scheduling;

import com.clinicops.provider.ProviderWorkingHours;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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

        List<Instant[]> slots = SlotGenerator.generateForDay(date, List.of(window), 30, Instant.EPOCH);

        assertThat(slots).hasSize(2);
        assertThat(slots.get(0)[0]).isEqualTo(date.atTime(9, 0).toInstant(java.time.ZoneOffset.UTC));
        assertThat(slots.get(0)[1]).isEqualTo(date.atTime(9, 30).toInstant(java.time.ZoneOffset.UTC));
        assertThat(slots.get(1)[0]).isEqualTo(date.atTime(9, 30).toInstant(java.time.ZoneOffset.UTC));
        assertThat(slots.get(1)[1]).isEqualTo(date.atTime(10, 0).toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    void dropsALeftoverRemainderRatherThanGeneratingAShortFinalSlot() {
        // 9:00-9:50, 30-minute slots: one whole slot fits (9:00-9:30), the
        // remaining 20 minutes (9:30-9:50) is unused, not a short slot.
        ProviderWorkingHours window = workingHours(LocalTime.of(9, 0), LocalTime.of(9, 50));

        List<Instant[]> slots = SlotGenerator.generateForDay(LocalDate.of(2026, 9, 14), List.of(window), 30, Instant.EPOCH);

        assertThat(slots).hasSize(1);
    }

    @Test
    void excludesSlotsThatStartBeforeNotBefore() {
        ProviderWorkingHours window = workingHours(LocalTime.of(9, 0), LocalTime.of(10, 0));
        LocalDate date = LocalDate.of(2026, 9, 14);
        Instant notBefore = date.atTime(9, 30).toInstant(java.time.ZoneOffset.UTC);

        List<Instant[]> slots = SlotGenerator.generateForDay(date, List.of(window), 30, notBefore);

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0)[0]).isEqualTo(notBefore);
    }

    @Test
    void emptyWorkingHoursProducesNoSlots() {
        assertThat(SlotGenerator.generateForDay(LocalDate.of(2026, 9, 14), List.of(), 30, Instant.EPOCH)).isEmpty();
    }

    @Test
    void multipleWindowsInOneDayEachProduceTheirOwnSlots() {
        // A lunch-break split shift: 9-12 and 13-15.
        ProviderWorkingHours morning = workingHours(LocalTime.of(9, 0), LocalTime.of(12, 0));
        ProviderWorkingHours afternoon = workingHours(LocalTime.of(13, 0), LocalTime.of(15, 0));

        List<Instant[]> slots = SlotGenerator.generateForDay(
                LocalDate.of(2026, 9, 14), List.of(morning, afternoon), 60, Instant.EPOCH);

        assertThat(slots).hasSize(5); // 3 in the morning, 2 in the afternoon
    }

    private ProviderWorkingHours workingHours(LocalTime start, LocalTime end) {
        ProviderWorkingHours hours = new ProviderWorkingHours();
        hours.setStartTime(start);
        hours.setEndTime(end);
        return hours;
    }
}
