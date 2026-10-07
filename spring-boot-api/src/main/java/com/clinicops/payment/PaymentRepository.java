package com.clinicops.payment;

import com.clinicops.analytics.DailyRevenue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    List<Payment> findAllByLabOrderIdAndTenantId(UUID labOrderId, UUID tenantId);

    List<Payment> findAllByDispenseRecordIdAndTenantId(UUID dispenseRecordId, UUID tenantId);

    List<Payment> findAllByImagingOrderIdAndTenantId(UUID imagingOrderId, UUID tenantId);

    /** Owner-agnostic lookup by the payment's own id - used by PaymentController/RefundService, which address a payment directly rather than through its appointment/lab-order owner. */
    Optional<Payment> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Collected-payment total per calendar day since `since`, for the
     * clinic-admin analytics dashboard (frontend phase O) - covers both
     * appointment and lab-order payments alike (this table's own dual
     * -owner shape), including `fee_auto_charged` rows, same as every
     * other reading of this table. UTC day boundaries, same convention as
     * AppointmentRepository.findDailyAppointmentVolume.
     */
    @Query(value = """
            SELECT CAST(created_at AS date) AS day, COALESCE(SUM(amount), 0) AS total
            FROM payments
            WHERE tenant_id = :tenantId AND created_at >= :since
            GROUP BY CAST(created_at AS date)
            ORDER BY day
            """, nativeQuery = true)
    List<DailyRevenue> findDailyRevenue(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    // ---- Phase 46: billing realism ----

    /** Amount already paid toward one invoice - the one query the new balance/overpayment-block logic hinges on. */
    @Query(value = "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE invoice_id = :invoiceId AND tenant_id = :tenantId",
            nativeQuery = true)
    BigDecimal sumAmountByInvoiceIdAndTenantId(@Param("invoiceId") UUID invoiceId, @Param("tenantId") UUID tenantId);

    /**
     * Retroactively links a deposit (or any other payment recorded before
     * an invoice existed) to the invoice once it's generated, so it's
     * immediately picked up by the balance calculation above - one method
     * per owner type, mirroring Payment's own exactly-one-owner shape.
     * flushAutomatically=true: InvoiceService.generateFor* calls
     * invoiceRepository.save(invoice) immediately before this - without a
     * forced flush, the pending Invoice insert is still unflushed when this
     * native-level UPDATE runs, and Postgres rejects it against
     * payments_invoice_id_fkey (found live, phase 46 verification).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Payment p SET p.invoiceId = :invoiceId WHERE p.tenantId = :tenantId AND p.appointmentId = :appointmentId AND p.invoiceId IS NULL")
    int linkUnlinkedPaymentsForAppointment(@Param("tenantId") UUID tenantId, @Param("appointmentId") UUID appointmentId, @Param("invoiceId") UUID invoiceId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Payment p SET p.invoiceId = :invoiceId WHERE p.tenantId = :tenantId AND p.labOrderId = :labOrderId AND p.invoiceId IS NULL")
    int linkUnlinkedPaymentsForLabOrder(@Param("tenantId") UUID tenantId, @Param("labOrderId") UUID labOrderId, @Param("invoiceId") UUID invoiceId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Payment p SET p.invoiceId = :invoiceId WHERE p.tenantId = :tenantId AND p.dispenseRecordId = :dispenseRecordId AND p.invoiceId IS NULL")
    int linkUnlinkedPaymentsForDispenseRecord(@Param("tenantId") UUID tenantId, @Param("dispenseRecordId") UUID dispenseRecordId, @Param("invoiceId") UUID invoiceId);

    @Modifying(flushAutomatically = true)
    @Query("UPDATE Payment p SET p.invoiceId = :invoiceId WHERE p.tenantId = :tenantId AND p.imagingOrderId = :imagingOrderId AND p.invoiceId IS NULL")
    int linkUnlinkedPaymentsForImagingOrder(@Param("tenantId") UUID tenantId, @Param("imagingOrderId") UUID imagingOrderId, @Param("invoiceId") UUID invoiceId);
}
