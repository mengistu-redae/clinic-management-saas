package com.clinicops.clinicgroup;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Cross-tenant by design, same reasoning as PlatformController - grouping
 * clinics into a chain is a platform-level action, not something any single
 * clinic's own staff can do to themselves. See CLAUDE.md's "Phase 45"
 * write-up.
 */
@RestController
@RequestMapping("/api/platform/clinic-groups")
public class ClinicGroupController {

    private final ClinicGroupRepository clinicGroupRepository;
    private final ClinicRepository clinicRepository;

    public ClinicGroupController(ClinicGroupRepository clinicGroupRepository, ClinicRepository clinicRepository) {
        this.clinicGroupRepository = clinicGroupRepository;
        this.clinicRepository = clinicRepository;
    }

    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public List<ClinicGroup> clinicGroups() {
        return clinicGroupRepository.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ClinicGroup clinicGroup(@PathVariable UUID id) {
        return clinicGroupRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Clinic group not found: " + id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ClinicGroup createClinicGroup(@Valid @RequestBody CreateClinicGroupRequest request) {
        ClinicGroup group = new ClinicGroup();
        group.setName(request.name());
        return clinicGroupRepository.save(group);
    }

    /**
     * Links an existing clinic into this group - the actual "opt in to
     * sharing" action. Idempotent (re-assigning to the same group is a
     * no-op); assigning to a different group moves it outright, there's no
     * "belongs to several groups at once" concept.
     */
    @PostMapping("/{id}/clinics/{clinicId}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Clinic assignClinic(@PathVariable UUID id, @PathVariable UUID clinicId) {
        ClinicGroup group = clinicGroupRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Clinic group not found: " + id));
        Clinic clinic = clinicRepository.findById(clinicId)
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + clinicId));
        clinic.setClinicGroupId(group.getId());
        return clinicRepository.save(clinic);
    }

    /** Opts a clinic back out of group-wide patient sharing entirely. */
    @PostMapping("/{id}/clinics/{clinicId}/remove")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public Clinic removeClinic(@PathVariable UUID id, @PathVariable UUID clinicId) {
        Clinic clinic = clinicRepository.findById(clinicId)
                .orElseThrow(() -> new NoSuchElementException("Clinic not found: " + clinicId));
        if (id.equals(clinic.getClinicGroupId())) {
            clinic.setClinicGroupId(null);
            clinic = clinicRepository.save(clinic);
        }
        return clinic;
    }

    public record CreateClinicGroupRequest(@NotBlank String name) {
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
