package com.clinicops.analytics;

/** Current snapshot count of appointments in one status - see AppointmentRepository.countByStatus. */
public interface StatusCount {
    String getStatus();
    Long getTotal();
}
