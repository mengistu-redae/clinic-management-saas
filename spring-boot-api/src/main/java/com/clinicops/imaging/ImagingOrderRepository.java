package com.clinicops.imaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImagingOrderRepository extends JpaRepository<ImagingOrder, UUID> {

    Optional<ImagingOrder> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ImagingOrder> findAllByTenantId(UUID tenantId);

    boolean existsByOrderRef(String orderRef);

    boolean existsByTenantIdAndClinicRef(UUID tenantId, String clinicRef);
}
