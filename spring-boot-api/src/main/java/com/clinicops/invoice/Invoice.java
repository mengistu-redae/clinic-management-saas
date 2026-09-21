package com.clinicops.invoice;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Getter
@Setter
public class Invoice extends BaseTenantEntity {

    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Column(name = "lab_order_id")
    private UUID labOrderId;

    @Column(name = "subtotal_amount", nullable = false)
    private BigDecimal subtotalAmount;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;
}
