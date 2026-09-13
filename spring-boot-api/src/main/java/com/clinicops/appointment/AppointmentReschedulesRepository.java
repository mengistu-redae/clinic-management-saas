package com.clinicops.appointment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AppointmentReschedulesRepository extends JpaRepository<AppointmentReschedule, UUID> {

    List<AppointmentReschedule> findAllByAppointmentId(UUID appointmentId);
}
