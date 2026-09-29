package com.clinicops.payment;

import com.clinicops.analytics.DailyRevenue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByAppointmentIdAndTenantId(UUID appointmentId, UUID tenantId);

    List<Payment> findAllByLabOrderIdAndTenantId(UUID labOrderId, UUID tenantId);

    List<Payment> findAllByDispenseRecordIdAndTenantId(UUID dispenseRecordId, UUID tenantId);

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
}
