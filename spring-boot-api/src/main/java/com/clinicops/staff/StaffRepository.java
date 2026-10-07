package com.clinicops.staff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {

    Optional<Staff> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Staff> findAllByTenantId(UUID tenantId);

    List<Staff> findAllByTenantIdAndStatus(UUID tenantId, String status);
}
