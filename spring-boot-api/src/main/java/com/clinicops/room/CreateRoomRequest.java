package com.clinicops.room;

import jakarta.validation.constraints.NotBlank;

public record CreateRoomRequest(@NotBlank String name) {
}
