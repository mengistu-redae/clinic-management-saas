package com.clinicops.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<Employee, UUID> {

    List<Employee> findAllByTenantId(UUID tenantId);

    List<Employee> findAllByTenantIdAndStatus(UUID tenantId, String status);

    Optional<Employee> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Employee> findByTenantIdAndAppUserId(UUID tenantId, UUID appUserId);
}
