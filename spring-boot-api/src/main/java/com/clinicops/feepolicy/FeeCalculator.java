package com.clinicops.feepolicy;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Applies a clinic's {@code fee_policies} tiers to a cancellation (and,
 * later, a lab-order cancellation - kept generic on purpose, exactly as the
 * kickoff spec asks: {@code calculate(tenantId, providerId, totalCost, dueAt)},
 * no appointment-specific parameter anywhere in the signature).
 *
 * Mirrors the reference bus-ticketing-saas project's {@code RefundCalculator}
 * almost exactly, semantics inverted (a *fee* percent charged for short
 * notice, not a *refund* percent given back for long notice) and reading
 * from one-row-per-tier rows instead of a JSON array.
 */
@Service
public class FeeCalculator {

    private final FeePolicyRepository feePolicyRepository;

    public FeeCalculator(FeePolicyRepository feePolicyRepository) {
        this.feePolicyRepository = feePolicyRepository;
    }

    /**
     * Returns zero if the clinic hasn't configured a policy (for this
     * provider or at all) - a missing policy is a configuration gap, not
     * grounds to block the caller's cancellation.
     */
    public BigDecimal calculate(UUID tenantId, UUID providerId, BigDecimal totalCost, Instant dueAt) {
        List<FeePolicy> tiers = feePolicyRepository.findAllByTenantIdAndProviderId(tenantId, providerId);
        if (tiers.isEmpty()) {
            tiers = feePolicyRepository.findAllByTenantIdAndProviderIdIsNull(tenantId);
        }
        if (tiers.isEmpty()) {
            return BigDecimal.ZERO;
        }

        long noticeHours = Duration.between(Instant.now(), dueAt).toHours();
        int feePercent = tiers.stream()
                // Tiers are meant to be authored highest-cutoff-first, but
                // sort defensively so storage order can never change which
                // tier applies.
                .sorted(Comparator.comparingInt(FeePolicy::getCutoffHours).reversed())
                .filter(tier -> noticeHours >= tier.getCutoffHours())
                .findFirst()
                .map(FeePolicy::getFeePercent)
                .orElse(0);

        return totalCost
                .multiply(BigDecimal.valueOf(feePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
