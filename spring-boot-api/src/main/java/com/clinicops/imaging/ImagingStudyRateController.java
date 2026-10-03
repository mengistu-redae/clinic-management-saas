package com.clinicops.imaging;

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
import java.util.Set;
import java.util.UUID;

/** Same CRUD shape as LabRateController - clinic_admin only, real delete since this is config, not a financial record. */
@RestController
public class ImagingStudyRateController {

    private static final Set<String> VALID_MODALITIES = Set.of("xray", "ultrasound", "ct", "mri", "other");

    private final ImagingStudyRateRepository imagingStudyRateRepository;

    public ImagingStudyRateController(ImagingStudyRateRepository imagingStudyRateRepository) {
        this.imagingStudyRateRepository = imagingStudyRateRepository;
    }

    @GetMapping("/api/clinic/imaging-study-rates")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public List<ImagingStudyRate> imagingStudyRates() {
        return imagingStudyRateRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/api/clinic/imaging-study-rates/{id}")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ImagingStudyRate imagingStudyRate(@PathVariable UUID id) {
        return imagingStudyRateRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Imaging study rate not found: " + id));
    }

    @PostMapping("/api/clinic/imaging-study-rates")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ImagingStudyRate createImagingStudyRate(@Valid @RequestBody CreateImagingStudyRateRequest request) {
        UUID tenantId = TenantContext.require();
        requireValidModality(request.modality());
        if (imagingStudyRateRepository.findByTenantIdAndStudyCode(tenantId, request.studyCode()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A rate for studyCode " + request.studyCode() + " already exists");
        }
        ImagingStudyRate rate = new ImagingStudyRate();
        rate.setTenantId(tenantId);
        rate.setStudyCode(request.studyCode());
        rate.setStudyName(request.studyName());
        rate.setModality(request.modality());
        rate.setBaseCharge(request.baseCharge());
        return imagingStudyRateRepository.save(rate);
    }

    @PostMapping("/api/clinic/imaging-study-rates/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ImagingStudyRate updateImagingStudyRate(@PathVariable UUID id, @RequestBody UpdateImagingStudyRateRequest request) {
        ImagingStudyRate rate = imagingStudyRateRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Imaging study rate not found: " + id));
        if (request.studyName() != null) {
            rate.setStudyName(request.studyName());
        }
        if (request.modality() != null) {
            requireValidModality(request.modality());
            rate.setModality(request.modality());
        }
        if (request.baseCharge() != null) {
            rate.setBaseCharge(request.baseCharge());
        }
        return imagingStudyRateRepository.save(rate);
    }

    /** Real delete - config, not a financial record; already-priced/snapshotted orders are unaffected. */
    @PostMapping("/api/clinic/imaging-study-rates/{id}/delete")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public ImagingStudyRate deleteImagingStudyRate(@PathVariable UUID id) {
        ImagingStudyRate rate = imagingStudyRateRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Imaging study rate not found: " + id));
        imagingStudyRateRepository.delete(rate);
        return rate;
    }

    private void requireValidModality(String modality) {
        if (!VALID_MODALITIES.contains(modality)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "modality must be one of " + VALID_MODALITIES);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
