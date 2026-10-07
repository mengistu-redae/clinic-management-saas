package com.clinicops.staff;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Nested under the staff resource - an ad-hoc per-date shift, no recurring template concept. */
@RestController
public class ShiftController {

    private final StaffRepository staffRepository;
    private final ShiftRepository shiftRepository;

    public ShiftController(StaffRepository staffRepository, ShiftRepository shiftRepository) {
        this.staffRepository = staffRepository;
        this.shiftRepository = shiftRepository;
    }

    @GetMapping("/api/staff/{staffId}/shifts")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public List<Shift> shifts(@PathVariable UUID staffId) {
        requireOwnedStaff(staffId, TenantContext.require());
        return shiftRepository.findAllByStaffId(staffId);
    }

    /**
     * Rejects a window that overlaps an existing shift for the same staff
     * member on the same day (checked in-memory, no DB constraint exists
     * for this) - same shape as ProviderWorkingHoursController's own
     * overlap check.
     */
    @PostMapping("/api/staff/{staffId}/shifts")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Shift createShift(@PathVariable UUID staffId, @Valid @RequestBody CreateShiftRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedStaff(staffId, tenantId);

        if (!request.startTime().isBefore(request.endTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }
        boolean overlaps = shiftRepository.findAllByStaffIdAndShiftDate(staffId, request.shiftDate()).stream()
                .anyMatch(existing -> existing.getStartTime().isBefore(request.endTime())
                        && request.startTime().isBefore(existing.getEndTime()));
        if (overlaps) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This shift overlaps an existing one for this staff member and day");
        }

        Shift shift = new Shift();
        shift.setTenantId(tenantId);
        shift.setStaffId(staffId);
        shift.setShiftDate(request.shiftDate());
        shift.setStartTime(request.startTime());
        shift.setEndTime(request.endTime());
        shift.setNotes(request.notes());
        return shiftRepository.save(shift);
    }

    /** Partial update - only non-null request fields are applied. Re-checks the overlap rule against the shift's resulting date/time. */
    @PostMapping("/api/staff/{staffId}/shifts/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Shift updateShift(@PathVariable UUID staffId, @PathVariable UUID id, @RequestBody UpdateShiftRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedStaff(staffId, tenantId);
        Shift shift = shiftRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Shift not found: " + id));
        if (!shift.getStaffId().equals(staffId)) {
            throw new NoSuchElementException("Shift not found: " + id);
        }

        var newDate = request.shiftDate() != null ? request.shiftDate() : shift.getShiftDate();
        var newStart = request.startTime() != null ? request.startTime() : shift.getStartTime();
        var newEnd = request.endTime() != null ? request.endTime() : shift.getEndTime();
        if (!newStart.isBefore(newEnd)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }
        boolean overlaps = shiftRepository.findAllByStaffIdAndShiftDate(staffId, newDate).stream()
                .anyMatch(existing -> !existing.getId().equals(id)
                        && existing.getStartTime().isBefore(newEnd)
                        && newStart.isBefore(existing.getEndTime()));
        if (overlaps) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This shift overlaps an existing one for this staff member and day");
        }

        shift.setShiftDate(newDate);
        shift.setStartTime(newStart);
        shift.setEndTime(newEnd);
        if (request.notes() != null) {
            shift.setNotes(request.notes());
        }
        return shiftRepository.save(shift);
    }

    private void requireOwnedStaff(UUID staffId, UUID tenantId) {
        staffRepository.findByIdAndTenantId(staffId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Staff member not found: " + staffId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
