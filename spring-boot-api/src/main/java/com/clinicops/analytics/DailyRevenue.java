package com.clinicops.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One day's collected-payment total - see PaymentRepository.findDailyRevenue. Covers both appointment and lab-order payments, same as Payment's own dual-owner shape. */
public interface DailyRevenue {
    LocalDate getDay();
    BigDecimal getTotal();
}
