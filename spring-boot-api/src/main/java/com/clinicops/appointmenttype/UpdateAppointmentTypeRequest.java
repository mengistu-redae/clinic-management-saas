package com.clinicops.appointmenttype;

import java.math.BigDecimal;

/** Partial update - only non-null fields are applied. */
public record UpdateAppointmentTypeRequest(String name, Integer durationMinutes, BigDecimal priceAmount, String status) {
}
