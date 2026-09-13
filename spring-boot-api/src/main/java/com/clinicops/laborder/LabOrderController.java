package com.clinicops.laborder;

import com.clinicops.labrate.NoLabRateConfiguredException;
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

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Clinical content - provider + clinic_admin only, no front_desk access,
 * same reasoning phase 4 already applied to encounters.
 */
@RestController
public class LabOrderController {

    private final LabOrderService labOrderService;

    public LabOrderController(LabOrderService labOrderService) {
        this.labOrderService = labOrderService;
    }

    @PostMapping("/api/lab-orders")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests createLabOrder(@Valid @RequestBody CreateLabOrderRequest request) {
        return labOrderService.create(TenantContext.require(), request);
    }

    @GetMapping("/api/lab-orders")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public List<LabOrder> labOrders() {
        return labOrderService.listForTenant(TenantContext.require());
    }

    @GetMapping("/api/lab-orders/{id}")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests labOrder(@PathVariable UUID id) {
        return labOrderService.get(id, TenantContext.require());
    }

    @PostMapping("/api/lab-orders/{id}/update")
    @PreAuthorize("hasAnyRole('PROVIDER', 'CLINIC_ADMIN')")
    public LabOrderWithTests updateLabOrder(@PathVariable UUID id, @RequestBody UpdateLabOrderRequest request) {
        return labOrderService.update(id, TenantContext.require(), request);
    }

    /** Two-factor public tracking - ref + the patient's own phone must match, otherwise 404 identically to an unknown ref. Status/timestamps only - see LabOrderTrackingView. */
    @GetMapping("/api/lab-orders/track/{orderRef}")
    public LabOrderTrackingView trackLabOrder(@PathVariable String orderRef, @RequestParam String phone) {
        return labOrderService.trackByRefAndPhone(orderRef, phone);
    }

    @ExceptionHandler(EncounterPatientMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleEncounterPatientMismatch(EncounterPatientMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(RestrictedTestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleRestrictedTest(RestrictedTestException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidLabOrderTestsException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleInvalidTests(InvalidLabOrderTestsException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoLabRateConfiguredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleNoRateConfigured(NoLabRateConfiguredException e) {
        return e.getMessage();
    }

    @ExceptionHandler(InvalidLabOrderStatusException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleInvalidStatus(InvalidLabOrderStatusException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
