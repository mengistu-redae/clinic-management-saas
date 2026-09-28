package com.clinicops.finance;

import com.clinicops.accounting.AccountRepository;
import com.clinicops.tenant.TenantContext;
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
import java.util.UUID;

/**
 * Same CRUD shape as AccountController/EmployeeController, plus a real
 * hard {@code /delete} - a missing budget row simply means "no target
 * set for this account/period," a well-defined fallback with nothing left
 * inconsistent, same reasoning FeePolicy/LabRate use for their own hard
 * deletes.
 */
@RestController
public class BudgetController {

    private final BudgetRepository budgetRepository;
    private final AccountRepository accountRepository;

    public BudgetController(BudgetRepository budgetRepository, AccountRepository accountRepository) {
        this.budgetRepository = budgetRepository;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/api/clinic/budgets")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<Budget> budgets(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month) {
        UUID tenantId = TenantContext.require();
        return year != null && month != null
                ? budgetRepository.findAllByTenantIdAndYearAndMonth(tenantId, year, month)
                : budgetRepository.findAllByTenantId(tenantId);
    }

    @GetMapping("/api/clinic/budgets/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Budget budget(@PathVariable UUID id) {
        return budgetRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Budget not found: " + id));
    }

    @PostMapping("/api/clinic/budgets")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Budget createBudget(@Valid @RequestBody CreateBudgetRequest request) {
        UUID tenantId = TenantContext.require();
        accountRepository.findByIdAndTenantId(request.accountId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + request.accountId()));
        if (budgetRepository.findByTenantIdAndAccountIdAndYearAndMonth(tenantId, request.accountId(), request.year(), request.month()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A budget already exists for this account and period - update it instead");
        }
        Budget budget = new Budget();
        budget.setTenantId(tenantId);
        budget.setAccountId(request.accountId());
        budget.setYear(request.year());
        budget.setMonth(request.month());
        budget.setAmount(request.amount());
        return budgetRepository.save(budget);
    }

    @PostMapping("/api/clinic/budgets/{id}/update")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Budget updateBudget(@PathVariable UUID id, @RequestBody UpdateBudgetRequest request) {
        Budget budget = budgetRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Budget not found: " + id));
        if (request.amount() != null) {
            budget.setAmount(request.amount());
        }
        return budgetRepository.save(budget);
    }

    @PostMapping("/api/clinic/budgets/{id}/delete")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public void deleteBudget(@PathVariable UUID id) {
        Budget budget = budgetRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Budget not found: " + id));
        budgetRepository.delete(budget);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
