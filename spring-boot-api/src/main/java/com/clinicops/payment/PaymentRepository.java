package com.clinicops.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    List<Payment> findAllByLabOrderIdAndTenantId(UUID labOrderId, UUID tenantId);
}
