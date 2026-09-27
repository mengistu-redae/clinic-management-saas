package com.clinicops.accounting;

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
import java.util.Set;
import java.util.UUID;

/**
 * Same phase-5/20 CRUD shape as RoomController/MedicationController -
 * controller calls the repository directly, no dedicated service bean
 * (plain single-row CRUD, no cross-cutting logic here - that lives in
 * {@link AccountSeedingService}/{@link JournalService}). {@code accountant}
 * + {@code clinic_admin} only, matching MedicationController's own "no
 * other role has any access" gate - the ledger is as role-siloed as the
 * pharmacy catalog.
 */
@RestController
public class AccountController {

    private static final Set<String> VALID_TYPES = Set.of("asset", "liability", "equity", "revenue", "expense");
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final AccountRepository accountRepository;
    private final AccountSeedingService accountSeedingService;

    public AccountController(AccountRepository accountRepository, AccountSeedingService accountSeedingService) {
        this.accountRepository = accountRepository;
        this.accountSeedingService = accountSeedingService;
    }

    @GetMapping("/api/clinic/accounts")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public List<Account> accounts(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        accountSeedingService.ensureSeeded(tenantId);
        return status == null || status.isBlank()
                ? accountRepository.findAllByTenantId(tenantId)
                : accountRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/clinic/accounts/{id}")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Account account(@PathVariable UUID id) {
        return accountRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
    }

    @PostMapping("/api/clinic/accounts")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Account createAccount(@Valid @RequestBody CreateAccountRequest request) {
        if (!VALID_TYPES.contains(request.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be one of " + VALID_TYPES);
        }
        UUID tenantId = TenantContext.require();
        accountSeedingService.ensureSeeded(tenantId);
        if (accountRepository.findByTenantIdAndCode(tenantId, request.code()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with code " + request.code() + " already exists");
        }
        Account account = new Account();
        account.setTenantId(tenantId);
        account.setCode(request.code());
        account.setName(request.name());
        account.setType(request.type());
        return accountRepository.save(account);
    }

    @PostMapping("/api/clinic/accounts/{id}/update")
    @PreAuthorize("hasAnyRole('ACCOUNTANT', 'CLINIC_ADMIN')")
    public Account updateAccount(@PathVariable UUID id, @RequestBody UpdateAccountRequest request) {
        Account account = accountRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Account not found: " + id));
        if (request.name() != null) {
            account.setName(request.name());
        }
        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            account.setStatus(request.status());
        }
        return accountRepository.save(account);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
