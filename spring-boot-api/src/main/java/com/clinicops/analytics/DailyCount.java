package com.clinicops.analytics;

import java.time.LocalDate;

/** One day's appointment-volume bucket - see AppointmentRepository.findDailyAppointmentVolume. */
public interface DailyCount {
    LocalDate getDay();
    Long getTotal();
}
