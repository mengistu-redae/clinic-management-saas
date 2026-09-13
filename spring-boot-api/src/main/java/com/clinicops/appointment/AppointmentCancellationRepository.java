package com.clinicops.appointment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AppointmentCancellationRepository extends JpaRepository<AppointmentCancellation, UUID> {
}
