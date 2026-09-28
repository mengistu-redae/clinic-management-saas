package com.clinicops.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    List<PayrollRun> findAllByTenantIdOrderByYearDescMonthDesc(UUID tenantId);

    Optional<PayrollRun> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<PayrollRun> findByTenantIdAndYearAndMonth(UUID tenantId, int year, int month);
}
