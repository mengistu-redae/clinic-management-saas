package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalyteDefinitionRepository extends JpaRepository<AnalyteDefinition, UUID> {

    List<AnalyteDefinition> findAllByTenantIdAndTestCodeOrderByDisplayOrder(UUID tenantId, String testCode);

    List<AnalyteDefinition> findAllByTenantIdOrderByTestCodeAscDisplayOrderAsc(UUID tenantId);

    Optional<AnalyteDefinition> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<AnalyteDefinition> findByTenantIdAndTestCodeAndAnalyteName(UUID tenantId, String testCode, String analyteName);

    boolean existsByTenantIdAndTestCodeAndAnalyteName(UUID tenantId, String testCode, String analyteName);
}
