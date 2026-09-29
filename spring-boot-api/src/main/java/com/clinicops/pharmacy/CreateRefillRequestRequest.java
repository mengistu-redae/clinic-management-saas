package com.clinicops.pharmacy;

/** notes is an optional freeform note from the patient (e.g. "running low, need before Friday"). */
public record CreateRefillRequestRequest(String notes) {
}
