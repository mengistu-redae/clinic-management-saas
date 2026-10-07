package com.clinicops.staff;

import com.clinicops.tenant.TenantContext;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Nested under the shift resource - one Attendance row per shift
 * (upsert-in-place: a clinic_admin correcting a mis-marked day replaces
 * this row's status/notes rather than adding a new one), not a real
 * clock-in/out punch.
 */
@RestController
public class AttendanceController {

    private static final Set<String> VALID_STATUSES = Set.of("present", "absent", "leave");

    private final ShiftRepository shiftRepository;
    private final AttendanceRepository attendanceRepository;
    private final AppUserRepository appUserRepository;

    public AttendanceController(
            ShiftRepository shiftRepository,
            AttendanceRepository attendanceRepository,
            AppUserRepository appUserRepository) {
        this.shiftRepository = shiftRepository;
        this.attendanceRepository = attendanceRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/api/shifts/{shiftId}/attendance")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Attendance attendance(@PathVariable UUID shiftId) {
        UUID tenantId = TenantContext.require();
        requireOwnedShift(shiftId, tenantId);
        return attendanceRepository.findByShiftIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("No attendance recorded yet for shift: " + shiftId));
    }

    @PostMapping("/api/shifts/{shiftId}/attendance")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Attendance markAttendance(@PathVariable UUID shiftId, @Valid @RequestBody MarkAttendanceRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedShift(shiftId, tenantId);
        if (!VALID_STATUSES.contains(request.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
        }

        Attendance attendance = attendanceRepository.findByShiftIdAndTenantId(shiftId, tenantId)
                .orElseGet(() -> {
                    Attendance created = new Attendance();
                    created.setTenantId(tenantId);
                    created.setShiftId(shiftId);
                    return created;
                });
        attendance.setStatus(request.status());
        attendance.setNotes(request.notes());
        attendance.setRecordedBy(currentAppUserId());
        attendance.setUpdatedAt(Instant.now());
        return attendanceRepository.save(attendance);
    }

    private void requireOwnedShift(UUID shiftId, UUID tenantId) {
        shiftRepository.findByIdAndTenantId(shiftId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Shift not found: " + shiftId));
    }

    /** Resolves the caller's own AppUser row from their Keycloak subject - null if they've never been provisioned one, which shouldn't happen for an authenticated staff request but isn't worth hard-failing over. */
    private UUID currentAppUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return appUserRepository.findByKeycloakUserId(jwt.getSubject())
                    .map(AppUser::getId)
                    .orElse(null);
        }
        return null;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
