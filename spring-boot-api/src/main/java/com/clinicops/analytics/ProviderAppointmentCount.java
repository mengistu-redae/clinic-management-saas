package com.clinicops.analytics;

import java.util.UUID;

/** Appointment count per provider over the analytics window - see AppointmentRepository.countByProviderSince. */
public interface ProviderAppointmentCount {
    UUID getProviderId();
    Long getTotal();
}
