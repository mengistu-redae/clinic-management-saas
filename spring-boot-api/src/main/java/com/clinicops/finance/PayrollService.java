package com.clinicops.finance;

import com.clinicops.accounting.JournalEntry;
import com.clinicops.accounting.JournalService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A single bean, plain {@code @Transactional} - not a contended resource
 * the way slot booking is, same reasoning EncounterService/DispenseService
 * already give for skipping the Redis-lock split-bean pattern. One
 * {@link JournalService#postForPayroll} call per active employee (a
 * separate balanced entry each, not one entry for the whole run) so a
 * single employee's pay can be traced or reversed on its own.
 */
@Service
public class PayrollService {

    private final EmployeeRepository employeeRepository;
    private final PayrollRunRepository payrollRunRepository;
    private final PayrollPaymentRepository payrollPaymentRepository;
    private final JournalService journalService;

    public PayrollService(
            EmployeeRepository employeeRepository,
            PayrollRunRepository payrollRunRepository,
            PayrollPaymentRepository payrollPaymentRepository,
            JournalService journalService) {
        this.employeeRepository = employeeRepository;
        this.payrollRunRepository = payrollRunRepository;
        this.payrollPaymentRepository = payrollPaymentRepository;
        this.journalService = journalService;
    }

    @Transactional
    public PayrollRunWithPayments runPayroll(UUID tenantId, RunPayrollRequest request, UUID runBy) {
        if (payrollRunRepository.findByTenantIdAndYearAndMonth(tenantId, request.year(), request.month()).isPresent()) {
            throw new PayrollAlreadyRunException(
                    "Payroll for " + request.year() + "-" + request.month() + " has already been run");
        }

        List<Employee> activeEmployees = employeeRepository.findAllByTenantIdAndStatus(tenantId, "active");
        if (activeEmployees.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active employees to pay");
        }

        BigDecimal totalAmount = activeEmployees.stream()
                .map(Employee::getSalaryAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PayrollRun run = new PayrollRun();
        run.setTenantId(tenantId);
        run.setYear(request.year());
        run.setMonth(request.month());
        run.setTotalAmount(totalAmount);
        run.setRunBy(runBy);
        run = payrollRunRepository.save(run);

        String description = "Payroll " + request.year() + "-" + request.month();
        for (Employee employee : activeEmployees) {
            JournalEntry entry = journalService.postForPayroll(
                    tenantId, employee.getId(), employee.getSalaryAmount(), description, runBy);

            PayrollPayment payment = new PayrollPayment();
            payment.setTenantId(tenantId);
            payment.setPayrollRunId(run.getId());
            payment.setEmployeeId(employee.getId());
            payment.setAmount(employee.getSalaryAmount());
            payment.setJournalEntryId(entry.getId());
            payrollPaymentRepository.save(payment);
        }

        return new PayrollRunWithPayments(run, payrollPaymentRepository.findAllByPayrollRunId(run.getId()));
    }

    public PayrollRunWithPayments get(UUID tenantId, UUID id) {
        PayrollRun run = payrollRunRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Payroll run not found: " + id));
        return new PayrollRunWithPayments(run, payrollPaymentRepository.findAllByPayrollRunId(run.getId()));
    }

    public List<PayrollRun> list(UUID tenantId) {
        return payrollRunRepository.findAllByTenantIdOrderByYearDescMonthDesc(tenantId);
    }
}
