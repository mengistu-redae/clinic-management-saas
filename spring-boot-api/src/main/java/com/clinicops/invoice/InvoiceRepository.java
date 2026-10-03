package com.clinicops.invoice;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    Optional<Invoice> findByLabOrderIdAndTenantId(UUID labOrderId, UUID tenantId);

    Optional<Invoice> findByDispenseRecordIdAndTenantId(UUID dispenseRecordId, UUID tenantId);

    /** Added for phase 40 (insurance claims) - a claim is filed against an invoice by its own id, not looked up by owner. */
    Optional<Invoice> findByIdAndTenantId(UUID id, UUID tenantId);
}
