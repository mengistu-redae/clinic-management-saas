package com.clinicops.finance;

import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** {@code accountant}+{@code clinic_admin} only, same gate as every other finance/accounting endpoint - payroll is financial staff data, not a general-access resource. */
@RestController
public class PayrollController {

    private final PayrollService payrollService;
    private final CurrentUserService currentUserService;

    public PayrollController(PayrollService payrollService, CurrentUserService currentUserService) {
        this.payrollService = payrollService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/api/clinic/payroll-runs")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public PayrollRunWithPayments runPayroll(@Valid @RequestBody RunPayrollRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID runBy = currentUserService.resolveInternalUserId(jwt);
        return payrollService.runPayroll(tenantId, request, runBy);
    }

    @GetMapping("/api/clinic/payroll-runs")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<PayrollRun> payrollRuns() {
        return payrollService.list(TenantContext.require());
    }

    @GetMapping("/api/clinic/payroll-runs/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public PayrollRunWithPayments payrollRun(@PathVariable UUID id) {
        return payrollService.get(TenantContext.require(), id);
    }

    @ExceptionHandler(PayrollAlreadyRunException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleAlreadyRun(PayrollAlreadyRunException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
