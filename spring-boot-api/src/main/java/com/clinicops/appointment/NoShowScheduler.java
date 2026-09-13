package com.clinicops.appointment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Flips past appointments to `no_show` purely so they leave the active
 * worklist - a visibility mechanism only, same principle as the reference
 * project's TripLifecycleScheduler: the real-time gate check
 * (CheckInService.checkIn, comparing live against the slot's own end time)
 * never depends on this poller having already run for a given appointment,
 * so its lag can never produce a wrong real-time decision.
 */
@Component
public class NoShowScheduler {

    private static final Logger log = LoggerFactory.getLogger(NoShowScheduler.class);

    private final AppointmentRepository appointmentRepository;

    public NoShowScheduler(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void flipStaleBookedToNoShow() {
        int flipped = appointmentRepository.flipStaleBookedToNoShow(Instant.now());
        if (flipped > 0) {
            log.info("Flipped {} stale booked appointment(s) to no_show", flipped);
        }
    }
}
