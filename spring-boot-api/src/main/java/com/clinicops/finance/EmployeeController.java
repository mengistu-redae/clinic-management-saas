package com.clinicops.finance;

import com.clinicops.tenant.TenantContext;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Same phase-5/20/21 CRUD shape as RoomController/MedicationController/
 * AccountController - controller calls the repository directly, no
 * dedicated service bean for plain single-row CRUD. {@code accountant}+
 * {@code clinic_admin} only - salary is exactly the kind of financial
 * staff data those two roles already own the rest of (accounts, the
 * ledger).
 */
@RestController
public class EmployeeController {

    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final EmployeeRepository employeeRepository;
    private final AppUserRepository appUserRepository;

    public EmployeeController(EmployeeRepository employeeRepository, AppUserRepository appUserRepository) {
        this.employeeRepository = employeeRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/api/clinic/employees")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<Employee> employees(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? employeeRepository.findAllByTenantId(tenantId)
                : employeeRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/clinic/employees/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Employee employee(@PathVariable UUID id) {
        return employeeRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + id));
    }

    @PostMapping("/api/clinic/employees")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Employee createEmployee(@Valid @RequestBody CreateEmployeeRequest request) {
        UUID tenantId = TenantContext.require();
        AppUser appUser = appUserRepository.findFirstByEmail(request.email())
                .orElseThrow(() -> new NoSuchElementException(
                        "No account has ever logged in with that email - they must log in once first: " + request.email()));
        if (employeeRepository.findByTenantIdAndAppUserId(tenantId, appUser.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already on payroll for this clinic");
        }
        Employee employee = new Employee();
        employee.setTenantId(tenantId);
        employee.setAppUserId(appUser.getId());
        employee.setFullName(appUser.getDisplayName());
        employee.setEmail(appUser.getEmail());
        employee.setSalaryAmount(request.salaryAmount());
        return employeeRepository.save(employee);
    }

    @PostMapping("/api/clinic/employees/{id}/update")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Employee updateEmployee(@PathVariable UUID id, @RequestBody UpdateEmployeeRequest request) {
        Employee employee = employeeRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Employee not found: " + id));
        if (request.salaryAmount() != null) {
            if (request.salaryAmount().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "salaryAmount must be positive");
            }
            employee.setSalaryAmount(request.salaryAmount());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            employee.setStatus(request.status());
        }
        return employeeRepository.save(employee);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
