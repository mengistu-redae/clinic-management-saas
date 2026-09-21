package com.clinicops.referral;

import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.ProviderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * No dedicated Writer-bean split (see CLAUDE.md's phase 5 write-up) - plain
 * single-row saves, no cross-bean self-invocation or lock/race concern.
 */
@Service
public class ReferralService {

    private static final Set<String> VALID_PRIORITIES = Set.of("routine", "urgent");
    private static final Set<String> VALID_STATUSES = Set.of("pending", "accepted", "scheduled", "completed", "declined");
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "declined");

    private final ReferralRepository referralRepository;
    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;

    public ReferralService(ReferralRepository referralRepository, PatientRepository patientRepository, ProviderRepository providerRepository) {
        this.referralRepository = referralRepository;
        this.patientRepository = patientRepository;
        this.providerRepository = providerRepository;
    }

    /**
     * Enforces the internal-xor-external rule that {@code CreateReferralRequest}
     * itself can't express: {@code receivingProviderId} (internal) and the
     * external fields are mutually exclusive, and at least one side must be
     * given - a referral that names neither a receiving provider nor an
     * external destination isn't a referral to anything.
     */
    @Transactional
    public Referral create(UUID tenantId, CreateReferralRequest request) {
        patientRepository.findByIdAndTenantId(request.patientId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + request.patientId()));
        providerRepository.findByIdAndTenantId(request.referringProviderId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Referring provider not found: " + request.referringProviderId()));

        boolean hasInternal = request.receivingProviderId() != null;
        boolean hasExternal = request.externalProviderName() != null || request.externalClinicName() != null;
        if (hasInternal == hasExternal) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give exactly one of receivingProviderId (internal) or externalProviderName/externalClinicName (external)");
        }
        if (hasInternal) {
            providerRepository.findByIdAndTenantId(request.receivingProviderId(), tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Receiving provider not found: " + request.receivingProviderId()));
        }

        String priority = request.priority() != null ? request.priority() : "routine";
        if (!VALID_PRIORITIES.contains(priority)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priority must be one of " + VALID_PRIORITIES);
        }

        Referral referral = new Referral();
        referral.setTenantId(tenantId);
        referral.setPatientId(request.patientId());
        referral.setEncounterId(request.encounterId());
        referral.setReferringProviderId(request.referringProviderId());
        referral.setReceivingProviderId(request.receivingProviderId());
        referral.setExternalProviderName(request.externalProviderName());
        referral.setExternalClinicName(request.externalClinicName());
        referral.setReferredToSpecialty(request.referredToSpecialty());
        referral.setReason(request.reason());
        referral.setClinicalSummary(request.clinicalSummary());
        referral.setPriority(priority);
        return referralRepository.save(referral);
    }

    @Transactional(readOnly = true)
    public List<Referral> listForTenant(UUID tenantId) {
        return referralRepository.findAllByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Referral get(UUID id, UUID tenantId) {
        return referralRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Referral not found: " + id));
    }

    /** Partial update - status/priority allow-listed; completedAt is set automatically the moment status becomes a terminal one (completed/declined), not client-supplied. */
    @Transactional
    public Referral update(UUID id, UUID tenantId, UpdateReferralRequest request) {
        Referral referral = referralRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Referral not found: " + id));

        if (request.status() != null) {
            if (!VALID_STATUSES.contains(request.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + VALID_STATUSES);
            }
            referral.setStatus(request.status());
            if (TERMINAL_STATUSES.contains(request.status()) && referral.getCompletedAt() == null) {
                referral.setCompletedAt(Instant.now());
            }
        }
        if (request.priority() != null) {
            if (!VALID_PRIORITIES.contains(request.priority())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "priority must be one of " + VALID_PRIORITIES);
            }
            referral.setPriority(request.priority());
        }
        if (request.notes() != null) {
            referral.setNotes(request.notes());
        }
        if (request.clinicalSummary() != null) {
            referral.setClinicalSummary(request.clinicalSummary());
        }
        return referralRepository.save(referral);
    }
}
