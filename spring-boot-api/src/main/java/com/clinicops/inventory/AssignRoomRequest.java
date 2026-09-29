package com.clinicops.inventory;

import java.util.UUID;

/** roomId null clears the assignment. */
public record AssignRoomRequest(UUID roomId) {
}
