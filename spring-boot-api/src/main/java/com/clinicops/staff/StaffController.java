package com.clinicops.staff;

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
 * Plain CRUD, no Writer-bean (see CLAUDE.md's phase 5 write-up) - every
 * write here is a single-row save, same shape as ProviderController.
 * clinic_admin-only throughout, including reads - the roster/attendance
 * module isn't exposed to any other role this phase.
 */
@RestController
public class StaffController {

    private static final Set<String> VALID_ROLES = Set.of(
            "clinic_admin", "provider", "front_desk", "pharmacist",
            "accountant", "lab_technician", "imaging_technologist");
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final StaffRepository staffRepository;
    private final AppUserRepository appUserRepository;

    public StaffController(StaffRepository staffRepository, AppUserRepository appUserRepository) {
        this.staffRepository = staffRepository;
        this.appUserRepository = appUserRepository;
    }

    @GetMapping("/api/staff")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public List<Staff> staff(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.require();
        return status == null || status.isBlank()
                ? staffRepository.findAllByTenantId(tenantId)
                : staffRepository.findAllByTenantIdAndStatus(tenantId, status);
    }

    @GetMapping("/api/staff/{id}")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Staff staffMember(@PathVariable UUID id) {
        return staffRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Staff member not found: " + id));
    }

    @PostMapping("/api/staff")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Staff createStaff(@Valid @RequestBody CreateStaffRequest request) {
        requireValidRole(request.role());
        Staff staffMember = new Staff();
        staffMember.setTenantId(TenantContext.require());
        staffMember.setFirstName(request.firstName());
        staffMember.setLastName(request.lastName());
        staffMember.setRole(request.role());
        return staffRepository.save(staffMember);
    }

    /** Partial update - only non-null request fields are applied. Setting status to "inactive"/"active" deactivates/reactivates - no separate endpoint, same convention as Provider. */
    @PostMapping("/api/staff/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Staff updateStaff(@PathVariable UUID id, @RequestBody UpdateStaffRequest request) {
        UUID tenantId = TenantContext.require();
        Staff staffMember = staffRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Staff member not found: " + id));

        if (request.firstName() != null) {
            staffMember.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            staffMember.setLastName(request.lastName());
        }
        if (request.role() != null) {
            requireValidRole(request.role());
            staffMember.setRole(request.role());
        }
        if (request.status() != null) {
            requireValidStatus(request.status());
            staffMember.setStatus(request.status());
        }
        return staffRepository.save(staffMember);
    }

    /** Links this staff member to an already-provisioned AppUser (someone who has logged in at least once) - no Keycloak call needed, same shape as Provider's own link-login. */
    @PostMapping("/api/staff/{id}/link-login")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Staff linkLogin(@PathVariable UUID id, @Valid @RequestBody LinkStaffLoginRequest request) {
        Staff staffMember = staffRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Staff member not found: " + id));
        AppUser appUser = appUserRepository.findFirstByEmail(request.email())
                .orElseThrow(() -> new NoSuchElementException(
                        "No account has ever logged in with that email - they must log in once first: " + request.email()));
        staffMember.setAppUserId(appUser.getId());
        return staffRepository.save(staffMember);
    }

    @PostMapping("/api/staff/{id}/unlink-login")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public Staff unlinkLogin(@PathVariable UUID id) {
        Staff staffMember = staffRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Staff member not found: " + id));
        staffMember.setAppUserId(null);
        return staffRepository.save(staffMember);
    }

    private void requireValidRole(String role) {
        if (!VALID_ROLES.contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be one of " + VALID_ROLES);
        }
    }

    private void requireValidStatus(String status) {
        if (!VALID_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
