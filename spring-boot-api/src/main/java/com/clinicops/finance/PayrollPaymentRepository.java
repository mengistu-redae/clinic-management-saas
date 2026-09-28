package com.clinicops.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PayrollPaymentRepository extends JpaRepository<PayrollPayment, UUID> {

    List<PayrollPayment> findAllByPayrollRunId(UUID payrollRunId);
}
