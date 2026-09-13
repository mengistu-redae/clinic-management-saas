package com.clinicops.appointmenttype;

import com.clinicops.common.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "appointment_types")
@Getter
@Setter
public class AppointmentType extends BaseTenantEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "price_amount", nullable = false)
    private BigDecimal priceAmount;

    @Column(nullable = false)
    private String status = "active";
}
