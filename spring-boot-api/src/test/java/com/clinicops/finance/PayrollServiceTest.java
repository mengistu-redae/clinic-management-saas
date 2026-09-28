package com.clinicops.finance;

import com.clinicops.accounting.JournalEntry;
import com.clinicops.accounting.JournalService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayrollServiceTest {

    private final EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    private final PayrollRunRepository payrollRunRepository = mock(PayrollRunRepository.class);
    private final PayrollPaymentRepository payrollPaymentRepository = mock(PayrollPaymentRepository.class);
    private final JournalService journalService = mock(JournalService.class);
    private final PayrollService service = new PayrollService(
            employeeRepository, payrollRunRepository, payrollPaymentRepository, journalService);

    private Employee activeEmployee(UUID tenantId, BigDecimal salary) {
        Employee employee = new Employee();
        employee.setId(UUID.randomUUID());
        employee.setTenantId(tenantId);
        employee.setSalaryAmount(salary);
        employee.setStatus("active");
        return employee;
    }

    @Test
    void runningPayrollPostsOneJournalEntryPerActiveEmployeeAndSavesAPayslipForEach() {
        UUID tenantId = UUID.randomUUID();
        UUID runBy = UUID.randomUUID();
        Employee a = activeEmployee(tenantId, new BigDecimal("1000.00"));
        Employee b = activeEmployee(tenantId, new BigDecimal("1500.00"));
        when(payrollRunRepository.findByTenantIdAndYearAndMonth(tenantId, 2026, 9)).thenReturn(Optional.empty());
        when(employeeRepository.findAllByTenantIdAndStatus(tenantId, "active")).thenReturn(List.of(a, b));
        when(payrollRunRepository.save(any(PayrollRun.class))).thenAnswer(inv -> {
            PayrollRun run = inv.getArgument(0);
            run.setId(UUID.randomUUID());
            return run;
        });
        when(journalService.postForPayroll(eq(tenantId), any(), any(), any(), eq(runBy))).thenAnswer(inv -> {
            JournalEntry entry = new JournalEntry();
            entry.setId(UUID.randomUUID());
            return entry;
        });
        when(payrollPaymentRepository.save(any(PayrollPayment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(payrollPaymentRepository.findAllByPayrollRunId(any())).thenReturn(List.of());

        PayrollRunWithPayments result = service.runPayroll(tenantId, new RunPayrollRequest(2026, 9), runBy);

        assertThat(result.run().getTotalAmount()).isEqualByComparingTo("2500.00");
        verify(journalService, times(2)).postForPayroll(eq(tenantId), any(), any(), any(), eq(runBy));
        verify(journalService).postForPayroll(tenantId, a.getId(), new BigDecimal("1000.00"), "Payroll 2026-9", runBy);
        verify(journalService).postForPayroll(tenantId, b.getId(), new BigDecimal("1500.00"), "Payroll 2026-9", runBy);
        verify(payrollPaymentRepository, times(2)).save(any(PayrollPayment.class));
    }

    @Test
    void runningPayrollForAnAlreadyRunMonthIsRejectedAndNeverPostsAnything() {
        UUID tenantId = UUID.randomUUID();
        PayrollRun existing = new PayrollRun();
        when(payrollRunRepository.findByTenantIdAndYearAndMonth(tenantId, 2026, 9)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.runPayroll(tenantId, new RunPayrollRequest(2026, 9), UUID.randomUUID()))
                .isInstanceOf(PayrollAlreadyRunException.class);

        verify(employeeRepository, never()).findAllByTenantIdAndStatus(any(), any());
        verify(journalService, never()).postForPayroll(any(), any(), any(), any(), any());
    }

    @Test
    void runningPayrollWithNoActiveEmployeesIsRejected() {
        UUID tenantId = UUID.randomUUID();
        when(payrollRunRepository.findByTenantIdAndYearAndMonth(tenantId, 2026, 9)).thenReturn(Optional.empty());
        when(employeeRepository.findAllByTenantIdAndStatus(tenantId, "active")).thenReturn(List.of());

        assertThatThrownBy(() -> service.runPayroll(tenantId, new RunPayrollRequest(2026, 9), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class);

        verify(payrollRunRepository, never()).save(any());
        verify(journalService, never()).postForPayroll(any(), any(), any(), any(), any());
    }
}
