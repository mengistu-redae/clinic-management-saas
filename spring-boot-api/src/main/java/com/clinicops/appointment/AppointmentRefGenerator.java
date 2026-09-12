package com.clinicops.appointment;

import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.Locale;
import java.util.UUID;

/**
 * Generates the human-facing appointment_ref and clinic_ref shown to a
 * patient - both NOT NULL, so generated before an appointment's first save
 * rather than derived from its own persisted id. Mirrors the reference
 * project's TicketNumberGenerator, simplified per the kickoff spec: a bare
 * 6-char appointment_ref (bounded uniqueness retry, checked rather than
 * trusted from randomness alone) plus an optional longer clinic-initials+
 * year+sequence clinic_ref.
 */
@Component
public class AppointmentRefGenerator {

    private static final int MAX_ATTEMPTS = 5;

    private final AppointmentRepository appointmentRepository;

    public AppointmentRefGenerator(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    public String nextAppointmentRef() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomHex(6);
            if (!appointmentRepository.existsByAppointmentRef(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique appointment reference after " + MAX_ATTEMPTS + " attempts");
    }

    /** clinic initials + year + a small sequence number, e.g. "DC-2026-0007" for "Demo Clinic". */
    public String nextClinicRef(UUID tenantId, String clinicName) {
        String prefix = clinicInitials(clinicName);
        int year = Year.now().getValue();
        for (int seq = 1; seq <= 9999; seq++) {
            String candidate = prefix + "-" + year + "-" + String.format("%04d", seq);
            if (!appointmentRepository.existsByTenantIdAndClinicRef(tenantId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique clinic reference for tenant " + tenantId);
    }

    private String clinicInitials(String clinicName) {
        if (clinicName == null || clinicName.isBlank()) {
            return "CL";
        }
        StringBuilder initials = new StringBuilder();
        for (String word : clinicName.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return initials.isEmpty() ? "CL" : initials.substring(0, Math.min(initials.length(), 4));
    }

    private String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, length).toUpperCase(Locale.ROOT);
    }
}
