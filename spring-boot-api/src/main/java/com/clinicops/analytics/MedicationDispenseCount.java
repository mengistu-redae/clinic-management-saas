package com.clinicops.analytics;

import java.util.UUID;

/** Total quantity dispensed per medication over the analytics window - see DispenseRecordRepository.countByMedicationSince. Embeds the medication name directly (the same "embed what the caller needs, don't force a second lookup" convention the phase-20 pharmacist-dashboard bug fix established), unlike ProviderAppointmentCount which leaves name resolution to the frontend. */
public interface MedicationDispenseCount {
    UUID getMedicationId();
    String getMedicationName();
    Long getTotal();
}
