package com.clinicops.feepolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeeCalculatorTest {

    private final FeePolicyRepository repository = mock(FeePolicyRepository.class);
    private final FeeCalculator calculator = new FeeCalculator(repository);

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID PROVIDER_ID = UUID.randomUUID();

    private FeePolicy tier(int cutoffHours, int feePercent) {
        FeePolicy policy = new FeePolicy();
        policy.setCutoffHours(cutoffHours);
        policy.setFeePercent(feePercent);
        return policy;
    }

    @Test
    void noTiersConfiguredAtAllYieldsZeroFee() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID)).thenReturn(List.of());
        when(repository.findAllByTenantIdAndProviderIdIsNull(TENANT_ID)).thenReturn(List.of());

        BigDecimal fee = calculator.calculate(TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now());

        assertThat(fee).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesTheHighestCutoffTierTheNoticePeriodClears() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID)).thenReturn(List.of());
        when(repository.findAllByTenantIdAndProviderIdIsNull(TENANT_ID)).thenReturn(List.of(
                tier(24, 0), tier(2, 50), tier(0, 100)));

        // 30 hours notice clears the 24h tier (0% fee).
        BigDecimal fee = calculator.calculate(
                TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now().plusSeconds(30 * 3600));
        assertThat(fee).isEqualByComparingTo("0.00");
    }

    @Test
    void middleTierAppliesWhenNoticeFallsBetweenCutoffs() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID)).thenReturn(List.of());
        when(repository.findAllByTenantIdAndProviderIdIsNull(TENANT_ID)).thenReturn(List.of(
                tier(24, 0), tier(2, 50), tier(0, 100)));

        // 5 hours notice clears the 2h tier but not the 24h one -> 50%.
        BigDecimal fee = calculator.calculate(
                TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now().plusSeconds(5 * 3600));
        assertThat(fee).isEqualByComparingTo("50.00");
    }

    @Test
    void zeroNoticeAppliesTheHighestFeeTier() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID)).thenReturn(List.of());
        when(repository.findAllByTenantIdAndProviderIdIsNull(TENANT_ID)).thenReturn(List.of(
                tier(24, 0), tier(2, 50), tier(0, 100)));

        BigDecimal fee = calculator.calculate(
                TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now());
        assertThat(fee).isEqualByComparingTo("100.00");
    }

    @Test
    void tiersSortCorrectlyRegardlessOfStorageOrder() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID)).thenReturn(List.of());
        // Deliberately out of order.
        when(repository.findAllByTenantIdAndProviderIdIsNull(TENANT_ID)).thenReturn(List.of(
                tier(0, 100), tier(24, 0), tier(2, 50)));

        BigDecimal fee = calculator.calculate(
                TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now().plusSeconds(5 * 3600));
        assertThat(fee).isEqualByComparingTo("50.00");
    }

    @Test
    void providerSpecificTiersReplaceTheClinicWideDefaultEntirelyRatherThanMerging() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID))
                .thenReturn(List.of(tier(0, 25)));

        BigDecimal fee = calculator.calculate(TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now());

        assertThat(fee).isEqualByComparingTo("25.00");
        // The clinic-wide default is never even queried once a provider override exists.
        verify(repository, never()).findAllByTenantIdAndProviderIdIsNull(any());
    }

    @Test
    void fallsBackToTheClinicWideDefaultWhenNoProviderOverrideExists() {
        when(repository.findAllByTenantIdAndProviderId(TENANT_ID, PROVIDER_ID)).thenReturn(List.of());
        when(repository.findAllByTenantIdAndProviderIdIsNull(TENANT_ID)).thenReturn(List.of(tier(0, 10)));

        BigDecimal fee = calculator.calculate(TENANT_ID, PROVIDER_ID, new BigDecimal("100.00"), Instant.now());

        assertThat(fee).isEqualByComparingTo("10.00");
    }
}
