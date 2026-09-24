package com.clinicops.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findAllByPaymentIdAndTenantId(UUID paymentId, UUID tenantId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r WHERE r.paymentId = :paymentId")
    BigDecimal sumAmountByPaymentId(@Param("paymentId") UUID paymentId);
}
