package com.clinicops.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    Optional<Invoice> findByLabOrderIdAndTenantId(UUID labOrderId, UUID tenantId);
}
