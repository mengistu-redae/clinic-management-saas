package com.clinicops.tenant;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 45: lists every clinic the caller is a Keycloak-org member of (not
 * just the one active for this particular request) - powers the frontend's
 * branch switcher. Any staff role; a caller with only one accessible clinic
 * (today's default, before any cross-branch membership is granted) just
 * gets a one-element list, same shape either way.
 */
@RestController
public class MyClinicsController {

    private final ClinicRepository clinicRepository;

    public MyClinicsController(ClinicRepository clinicRepository) {
        this.clinicRepository = clinicRepository;
    }

    @GetMapping("/api/me/clinics")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'FRONT_DESK', 'PROVIDER', 'PHARMACIST', 'ACCOUNTANT', 'LAB_TECHNICIAN', 'IMAGING_TECHNOLOGIST')")
    public List<Clinic> myClinics() {
        return clinicRepository.findAllById(TenantContext.accessibleClinicIds());
    }
}
