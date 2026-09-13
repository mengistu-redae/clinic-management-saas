package com.clinicops.labrate;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Same CRUD shape as phase 5's FeePolicyController - clinic_admin only, real delete since this is config, not a financial record. */
@RestController
public class LabRateController {

    private final LabTestRateRepository labTestRateRepository;

    public LabRateController(LabTestRateRepository labTestRateRepository) {
        this.labTestRateRepository = labTestRateRepository;
    }

    @GetMapping("/api/clinic/lab-rates")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public List<LabTestRate> labRates() {
        return labTestRateRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/api/clinic/lab-rates/{id}")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public LabTestRate labRate(@PathVariable UUID id) {
        return labTestRateRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Lab test rate not found: " + id));
    }

    @PostMapping("/api/clinic/lab-rates")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public LabTestRate createLabRate(@Valid @RequestBody CreateLabTestRateRequest request) {
        UUID tenantId = TenantContext.require();
        if (labTestRateRepository.findByTenantIdAndTestCode(tenantId, request.testCode()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A rate for testCode " + request.testCode() + " already exists");
        }
        LabTestRate rate = new LabTestRate();
        rate.setTenantId(tenantId);
        rate.setTestCode(request.testCode());
        rate.setBaseCharge(request.baseCharge());
        if (request.collectionFee() != null) {
            rate.setCollectionFee(request.collectionFee());
        }
        return labTestRateRepository.save(rate);
    }

    @PostMapping("/api/clinic/lab-rates/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public LabTestRate updateLabRate(@PathVariable UUID id, @RequestBody UpdateLabTestRateRequest request) {
        LabTestRate rate = labTestRateRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Lab test rate not found: " + id));
        if (request.baseCharge() != null) {
            rate.setBaseCharge(request.baseCharge());
        }
        if (request.collectionFee() != null) {
            rate.setCollectionFee(request.collectionFee());
        }
        return labTestRateRepository.save(rate);
    }

    /** Real delete - config, not a financial record; already-priced/snapshotted orders are unaffected. */
    @PostMapping("/api/clinic/lab-rates/{id}/delete")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public LabTestRate deleteLabRate(@PathVariable UUID id) {
        LabTestRate rate = labTestRateRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Lab test rate not found: " + id));
        labTestRateRepository.delete(rate);
        return rate;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
