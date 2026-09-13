package com.clinicops.feepolicy;

import com.clinicops.provider.ProviderRepository;
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
import java.util.UUID;

/**
 * Financial config - hasRole('CLINIC_ADMIN') on every endpoint, tighter
 * than the other phase-5 resources' 3-role reads. Real hard DELETE (see
 * CLAUDE.md's phase 5 write-up) - FeeCalculator already treats a missing
 * policy as a safe 0% fallback, so removing a tier leaves nothing
 * inconsistent, unlike providers/rooms/appointment-types.
 */
@RestController
public class FeePolicyController {

    private final FeePolicyRepository feePolicyRepository;
    private final ProviderRepository providerRepository;

    public FeePolicyController(FeePolicyRepository feePolicyRepository, ProviderRepository providerRepository) {
        this.feePolicyRepository = feePolicyRepository;
        this.providerRepository = providerRepository;
    }

    @GetMapping("/api/fee-policies")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public List<FeePolicy> feePolicies(@RequestParam(required = false) UUID providerId) {
        UUID tenantId = TenantContext.require();
        if (providerId != null) {
            return feePolicyRepository.findAllByTenantIdAndProviderId(tenantId, providerId);
        }
        return feePolicyRepository.findAllByTenantId(tenantId);
    }

    @GetMapping("/api/fee-policies/{id}")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public FeePolicy feePolicy(@PathVariable UUID id) {
        return feePolicyRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Fee policy not found: " + id));
    }

    @PostMapping("/api/fee-policies")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public FeePolicy createFeePolicy(@Valid @RequestBody CreateFeePolicyRequest request) {
        UUID tenantId = TenantContext.require();
        if (request.providerId() != null && providerRepository.findByIdAndTenantId(request.providerId(), tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerId does not belong to this clinic: " + request.providerId());
        }
        requireNoDuplicateTier(tenantId, request.providerId(), request.cutoffHours(), null);

        FeePolicy policy = new FeePolicy();
        policy.setTenantId(tenantId);
        policy.setProviderId(request.providerId());
        policy.setCutoffHours(request.cutoffHours());
        policy.setFeePercent(request.feePercent());
        return feePolicyRepository.save(policy);
    }

    @PostMapping("/api/fee-policies/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public FeePolicy updateFeePolicy(@PathVariable UUID id, @RequestBody UpdateFeePolicyRequest request) {
        UUID tenantId = TenantContext.require();
        FeePolicy policy = feePolicyRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Fee policy not found: " + id));

        if (request.cutoffHours() != null) {
            requireNoDuplicateTier(tenantId, policy.getProviderId(), request.cutoffHours(), id);
            policy.setCutoffHours(request.cutoffHours());
        }
        if (request.feePercent() != null) {
            if (request.feePercent() < 0 || request.feePercent() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feePercent must be between 0 and 100");
            }
            policy.setFeePercent(request.feePercent());
        }
        return feePolicyRepository.save(policy);
    }

    @PostMapping("/api/fee-policies/{id}/delete")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public FeePolicy deleteFeePolicy(@PathVariable UUID id) {
        FeePolicy policy = feePolicyRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Fee policy not found: " + id));
        feePolicyRepository.delete(policy);
        return policy;
    }

    private void requireNoDuplicateTier(UUID tenantId, UUID providerId, int cutoffHours, UUID excludingId) {
        List<FeePolicy> existing = providerId == null
                ? feePolicyRepository.findAllByTenantIdAndProviderIdIsNull(tenantId)
                : feePolicyRepository.findAllByTenantIdAndProviderId(tenantId, providerId);
        boolean duplicate = existing.stream()
                .anyMatch(p -> p.getCutoffHours() == cutoffHours && !p.getId().equals(excludingId));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A tier with cutoffHours=" + cutoffHours + " already exists for this provider/default");
        }
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
