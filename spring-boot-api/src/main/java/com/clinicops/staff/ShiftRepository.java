package com.clinicops.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftRepository extends JpaRepository<Shift, UUID> {

    Optional<Shift> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Shift> findAllByStaffId(UUID staffId);

    List<Shift> findAllByStaffIdAndShiftDate(UUID staffId, LocalDate shiftDate);

    List<Shift> findAllByTenantIdAndShiftDate(UUID tenantId, LocalDate shiftDate);
}
