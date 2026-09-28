package com.clinicops.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    List<Budget> findAllByTenantId(UUID tenantId);

    List<Budget> findAllByTenantIdAndYearAndMonth(UUID tenantId, int year, int month);

    Optional<Budget> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<Budget> findByTenantIdAndAccountIdAndYearAndMonth(UUID tenantId, UUID accountId, int year, int month);
}
