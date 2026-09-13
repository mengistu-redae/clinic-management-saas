package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LabOrderCancellationRepository extends JpaRepository<LabOrderCancellation, UUID> {

    List<LabOrderCancellation> findAllByLabOrderId(UUID labOrderId);
}
