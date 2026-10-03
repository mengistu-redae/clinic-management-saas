package com.clinicops.insurance;

import com.clinicops.patient.PatientRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * front_desk + clinic_admin only - this is the billing team's own record,
 * not a clinical one (no provider access), matching the role split the
 * user pinned for this whole module rather than Allergy/PatientController's
 * own three-role gate.
 */
@RestController
public class InsurancePolicyController {

    private static final Set<String> VALID_RANKS = Set.of("primary", "secondary");
    private static final Set<String> VALID_RELATIONSHIPS = Set.of("self", "spouse", "child", "other");
    private static final Set<String> VALID_STATUSES = Set.of("active", "inactive");

    private final InsurancePolicyRepository insurancePolicyRepository;
    private final PatientRepository patientRepository;

    public InsurancePolicyController(InsurancePolicyRepository insurancePolicyRepository, PatientRepository patientRepository) {
        this.insurancePolicyRepository = insurancePolicyRepository;
        this.patientRepository = patientRepository;
    }

    @GetMapping("/api/patients/{patientId}/insurance-policies")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public List<InsurancePolicy> policies(@PathVariable UUID patientId) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);
        return insurancePolicyRepository.findAllByPatientIdAndTenantId(patientId, tenantId);
    }

    @PostMapping("/api/patients/{patientId}/insurance-policies")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public InsurancePolicy createPolicy(@PathVariable UUID patientId, @Valid @RequestBody CreateInsurancePolicyRequest request) {
        UUID tenantId = TenantContext.require();
        requireOwnedPatient(patientId, tenantId);

        String rank = request.rank() != null ? request.rank() : "primary";
        requireValid(rank, VALID_RANKS, "rank");
        String relationship = request.relationshipToSubscriber() != null ? request.relationshipToSubscriber() : "self";
        requireValid(relationship, VALID_RELATIONSHIPS, "relationshipToSubscriber");

        InsurancePolicy policy = new InsurancePolicy();
        policy.setTenantId(tenantId);
        policy.setPatientId(patientId);
        policy.setPayerName(request.payerName());
        policy.setMemberId(request.memberId());
        policy.setGroupNumber(request.groupNumber());
        policy.setPlanType(request.planType());
        policy.setRank(rank);
        policy.setSubscriberName(request.subscriberName());
        policy.setRelationshipToSubscriber(relationship);
        policy.setEffectiveDate(request.effectiveDate());
        policy.setExpirationDate(request.expirationDate());
        return insurancePolicyRepository.save(policy);
    }

    /** Partial update - everything except patientId/payerName/memberId, same convention as UpdateAllergyRequest. */
    @PostMapping("/api/patients/{patientId}/insurance-policies/{id}/update")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public InsurancePolicy updatePolicy(
            @PathVariable UUID patientId, @PathVariable UUID id, @RequestBody UpdateInsurancePolicyRequest request) {
        UUID tenantId = TenantContext.require();
        InsurancePolicy policy = requireOwnedPolicy(patientId, id, tenantId);

        if (request.groupNumber() != null) {
            policy.setGroupNumber(request.groupNumber());
        }
        if (request.planType() != null) {
            policy.setPlanType(request.planType());
        }
        if (request.rank() != null) {
            requireValid(request.rank(), VALID_RANKS, "rank");
            policy.setRank(request.rank());
        }
        if (request.subscriberName() != null) {
            policy.setSubscriberName(request.subscriberName());
        }
        if (request.relationshipToSubscriber() != null) {
            requireValid(request.relationshipToSubscriber(), VALID_RELATIONSHIPS, "relationshipToSubscriber");
            policy.setRelationshipToSubscriber(request.relationshipToSubscriber());
        }
        if (request.effectiveDate() != null) {
            policy.setEffectiveDate(request.effectiveDate());
        }
        if (request.expirationDate() != null) {
            policy.setExpirationDate(request.expirationDate());
        }
        if (request.status() != null) {
            requireValid(request.status(), VALID_STATUSES, "status");
            policy.setStatus(request.status());
        }
        policy.setUpdatedAt(Instant.now());
        return insurancePolicyRepository.save(policy);
    }

    private InsurancePolicy requireOwnedPolicy(UUID patientId, UUID id, UUID tenantId) {
        InsurancePolicy policy = insurancePolicyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Insurance policy not found: " + id));
        if (!policy.getPatientId().equals(patientId)) {
            throw new NoSuchElementException("Insurance policy not found: " + id);
        }
        return policy;
    }

    private void requireOwnedPatient(UUID patientId, UUID tenantId) {
        patientRepository.findByIdAndTenantId(patientId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
    }

    private void requireValid(String value, Set<String> allowed, String field) {
        if (!allowed.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be one of " + allowed);
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
