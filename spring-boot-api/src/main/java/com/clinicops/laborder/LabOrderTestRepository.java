package com.clinicops.laborder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LabOrderTestRepository extends JpaRepository<LabOrderTest, UUID> {

    List<LabOrderTest> findAllByLabOrderId(UUID labOrderId);

    /** Full-replace semantics on update - see LabOrderService.replaceTests. */
    void deleteAllByLabOrderId(UUID labOrderId);
}
