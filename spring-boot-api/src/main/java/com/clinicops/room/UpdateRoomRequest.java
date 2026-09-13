package com.clinicops.room;

/** Partial update - only non-null fields are applied. */
public record UpdateRoomRequest(String name, String status) {
}
