package com.clinicops.appointment;

import com.clinicops.clinic.Clinic;
import com.clinicops.provider.Provider;
import com.clinicops.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProviderScheduleIntegrationTest extends AbstractIntegrationTest {

    @Test
    void onlyTodaysAppointmentsForTheCallingProviderAreReturned() throws Exception {
        Clinic clinic = createClinic("sched-" + UUID.randomUUID(), "Schedule Clinic");
        Provider caller = createProviderLinkedToAppUser(clinic.getId(), "Dr. Caller", "sched-caller");
        Provider other = createProviderLinkedToAppUser(clinic.getId(), "Dr. Other", "sched-other");
        var type = createAppointmentType(clinic.getId(), "Visit", 30, "50.00");

        // caller's own appointment, today.
        var todaySlot = createSlot(clinic.getId(), caller.getId(), type.getId(),
                Instant.now().plusSeconds(600), Instant.now().plusSeconds(1800));
        createBookedAppointment(clinic.getId(), todaySlot.getId(), null, caller.getId(), type.getId(), null);

        // caller's own appointment, but tomorrow - excluded.
        Instant tomorrowStart = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        var tomorrowSlot = createSlot(clinic.getId(), caller.getId(), type.getId(), tomorrowStart, tomorrowStart.plusSeconds(1800));
        createBookedAppointment(clinic.getId(), tomorrowSlot.getId(), null, caller.getId(), type.getId(), null);

        // a different provider's appointment, today - excluded.
        var otherSlot = createSlot(clinic.getId(), other.getId(), type.getId(),
                Instant.now().plusSeconds(900), Instant.now().plusSeconds(2100));
        createBookedAppointment(clinic.getId(), otherSlot.getId(), null, other.getId(), type.getId(), null);

        mockMvc.perform(get("/api/my-schedule").with(asProvider("sched-caller", clinic.getKeycloakOrgId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].providerId").value(caller.getId().toString()));
    }

    @Test
    void providerAccountWithNoLinkedProviderRowIs404() throws Exception {
        Clinic clinic = createClinic("sched-unlinked-" + UUID.randomUUID(), "Unlinked Clinic");

        mockMvc.perform(get("/api/my-schedule").with(asProvider("sched-unlinked-provider", clinic.getKeycloakOrgId())))
                .andExpect(status().isNotFound());
    }
}
