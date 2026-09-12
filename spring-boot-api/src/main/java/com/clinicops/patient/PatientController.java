package com.clinicops.patient;

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

@RestController
public class PatientController {

    private final PatientRepository patientRepository;
    private final PatientWriter patientWriter;

    public PatientController(PatientRepository patientRepository, PatientWriter patientWriter) {
        this.patientRepository = patientRepository;
        this.patientWriter = patientWriter;
    }

    /** Registering a walk-in - front-desk's entry point before booking them an appointment. */
    @PostMapping("/api/patients")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK')")
    public Patient createPatient(@Valid @RequestBody CreatePatientRequest request) {
        return patientWriter.register(TenantContext.require(), request);
    }

    @GetMapping("/api/patients")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public List<Patient> patients(@RequestParam(required = false) String query) {
        var tenantId = TenantContext.require();
        return (query == null || query.isBlank())
                ? patientRepository.findAllByTenantId(tenantId)
                : patientRepository.search(tenantId, query);
    }

    @GetMapping("/api/patients/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER')")
    public Patient patient(@PathVariable java.util.UUID id) {
        return patientRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + id));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
