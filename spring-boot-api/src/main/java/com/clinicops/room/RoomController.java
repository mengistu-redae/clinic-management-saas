package com.clinicops.room;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@RestController
public class RoomController {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final RoomRepository roomRepository;

    public RoomController(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @GetMapping("/api/rooms")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<Room> rooms(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? roomRepository.findAllByTenantId(tenantId)
                : roomRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/rooms/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public Room room(@PathVariable UUID id) {
        return roomRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Room not found: " + id));
    }

    @PostMapping("/api/rooms")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Room createRoom(@Valid @RequestBody CreateRoomRequest request) {
        Room room = new Room();
        room.setTenantId(TenantContext.require());
        room.setName(request.name());
        return roomRepository.save(room);
    }

    @PostMapping("/api/rooms/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Room updateRoom(@PathVariable UUID id, @RequestBody UpdateRoomRequest request) {
        Room room = roomRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Room not found: " + id));
        if (request.name() != null) {
            room.setName(request.name());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            room.setStatus(request.status());
        }
        return roomRepository.save(room);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
