package com.clinicops.laborder;

import com.clinicops.notification.NotificationRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyteResultServiceTest {

    private final LabOrderRepository labOrderRepository = mock(LabOrderRepository.class);
    private final LabOrderTestRepository labOrderTestRepository = mock(LabOrderTestRepository.class);
    private final AnalyteDefinitionRepository analyteDefinitionRepository = mock(AnalyteDefinitionRepository.class);
    private final AnalyteResultRepository analyteResultRepository = mock(AnalyteResultRepository.class);
    private final ProviderRepository providerRepository = mock(ProviderRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final AnalyteResultService service = new AnalyteResultService(
            labOrderRepository, labOrderTestRepository, analyteDefinitionRepository, analyteResultRepository,
            providerRepository, appUserRepository, notificationRepository, new ObjectMapper());

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID testId = UUID.randomUUID();

    private LabOrder orderWithStatus(String status) {
        LabOrder order = new LabOrder();
        order.setId(orderId);
        order.setTenantId(tenantId);
        order.setStatus(status);
        return order;
    }

    private LabOrderTest testLine(String testCode) {
        LabOrderTest test = new LabOrderTest();
        test.setId(testId);
        test.setTenantId(tenantId);
        test.setLabOrderId(orderId);
        test.setTestCode(testCode);
        return test;
    }

    private void stubHappyPath(String orderStatus, String testCode) {
        when(labOrderRepository.findByIdAndTenantId(orderId, tenantId)).thenReturn(Optional.of(orderWithStatus(orderStatus)));
        when(labOrderTestRepository.findById(testId)).thenReturn(Optional.of(testLine(testCode)));
        when(analyteResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void enteringResultsBeforeSpecimenIsSentIsRejected() {
        when(labOrderRepository.findByIdAndTenantId(orderId, tenantId)).thenReturn(Optional.of(orderWithStatus("ordered")));

        assertThatThrownBy(() -> service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "6.0"))))
                .isInstanceOf(InvalidLabOrderStatusException.class);
    }

    @Test
    void enteringResultsAfterSpecimenIsSentSucceeds() {
        stubHappyPath("in_transit", "CBC");

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "6.0")));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAnalyteName()).isEqualTo("WBC");
        assertThat(results.get(0).getValue()).isEqualTo("6.0");
    }

    @Test
    void aValueInsideTheNormalRangeIsFlaggedNormal() {
        stubHappyPath("in_transit", "CBC");
        AnalyteDefinition definition = definition("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"));
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC"))
                .thenReturn(Optional.of(definition));

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "6.0")));

        assertThat(results.get(0).getFlag()).isEqualTo("normal");
        assertThat(results.get(0).getUnit()).isEqualTo("x10^9/L");
        assertThat(results.get(0).getReferenceRangeDisplay()).isEqualTo("4.0-11.0");
        assertThat(results.get(0).getAnalyteDefinitionId()).isEqualTo(definition.getId());
    }

    @Test
    void aValueOutsideTheNormalRangeIsFlaggedAbnormal() {
        stubHappyPath("in_transit", "CBC");
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC"))
                .thenReturn(Optional.of(definition("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"))));

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "15.0")));

        assertThat(results.get(0).getFlag()).isEqualTo("abnormal");
    }

    @Test
    void aNonNumericValueAgainstANumericRangeIsLeftUnflaggedNotGuessed() {
        stubHappyPath("in_transit", "CBC");
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC"))
                .thenReturn(Optional.of(definition("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"))));

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "pending")));

        assertThat(results.get(0).getFlag()).isEqualTo("unflagged");
    }

    @Test
    void aQualitativeDefinitionWithNoNumericRangeIsNeverFlagged() {
        stubHappyPath("in_transit", "UA");
        AnalyteDefinition qualitative = definition("Protein", null, null);
        qualitative.setNormalRangeText("Negative");
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "UA", "Protein"))
                .thenReturn(Optional.of(qualitative));

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("Protein", "Negative")));

        assertThat(results.get(0).getFlag()).isEqualTo("unflagged");
        assertThat(results.get(0).getReferenceRangeDisplay()).isEqualTo("Negative");
    }

    @Test
    void anAnalyteWithNoMatchingCatalogDefinitionIsStillEnteredJustNeverFlagged() {
        stubHappyPath("in_transit", "CBC");
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "Unlisted"))
                .thenReturn(Optional.empty());

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("Unlisted", "3")));

        assertThat(results.get(0).getFlag()).isEqualTo("unflagged");
        assertThat(results.get(0).getAnalyteDefinitionId()).isNull();
    }

    @Test
    void aTestFromAnotherOrderIsNotFound() {
        when(labOrderRepository.findByIdAndTenantId(orderId, tenantId)).thenReturn(Optional.of(orderWithStatus("in_transit")));
        LabOrderTest wrongOrder = testLine("CBC");
        wrongOrder.setLabOrderId(UUID.randomUUID());
        when(labOrderTestRepository.findById(testId)).thenReturn(Optional.of(wrongOrder));

        assertThatThrownBy(() -> service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "6.0"))))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void reEnteringResultsReplacesThePreviousSet() {
        stubHappyPath("resulted", "CBC");

        service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "6.0")));

        verify(analyteResultRepository).deleteAllByLabOrderTestId(testId);
    }

    @Test
    void aValueBeyondTheCriticalRangeIsFlaggedCriticalNotJustAbnormal() {
        stubHappyPath("in_transit", "CBC");
        AnalyteDefinition definition = definitionWithCritical("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"), new BigDecimal("2.0"), new BigDecimal("30.0"));
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC"))
                .thenReturn(Optional.of(definition));

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "35.0")));

        assertThat(results.get(0).getFlag()).isEqualTo("critical");
    }

    @Test
    void aValueOutsideNormalButInsideCriticalStaysAbnormal() {
        stubHappyPath("in_transit", "CBC");
        AnalyteDefinition definition = definitionWithCritical("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"), new BigDecimal("2.0"), new BigDecimal("30.0"));
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC"))
                .thenReturn(Optional.of(definition));

        List<AnalyteResult> results = service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "15.0")));

        assertThat(results.get(0).getFlag()).isEqualTo("abnormal");
    }

    @Test
    void aCriticalResultNotifiesTheOrderingProviderWhenOneIsLinkedToARealLogin() {
        LabOrder order = orderWithStatus("in_transit");
        UUID providerId = UUID.randomUUID();
        order.setOrderingProviderId(providerId);
        when(labOrderRepository.findByIdAndTenantId(orderId, tenantId)).thenReturn(Optional.of(order));
        when(labOrderTestRepository.findById(testId)).thenReturn(Optional.of(testLine("CBC")));
        when(analyteResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnalyteDefinition definition = definitionWithCritical("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"), new BigDecimal("2.0"), new BigDecimal("30.0"));
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC")).thenReturn(Optional.of(definition));
        Provider provider = new Provider();
        provider.setId(providerId);
        UUID appUserId = UUID.randomUUID();
        provider.setAppUserId(appUserId);
        when(providerRepository.findByIdAndTenantId(providerId, tenantId)).thenReturn(Optional.of(provider));
        AppUser appUser = new AppUser();
        appUser.setEmail("dr@example.test");
        when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(appUser));

        service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "35.0")));

        verify(notificationRepository).save(argThat(n -> "dr@example.test".equals(n.getRecipient()) && "critical_lab_value".equals(n.getType())));
    }

    @Test
    void aCriticalResultSkipsNotificationWhenTheOrderingProviderHasNoLinkedLogin() {
        LabOrder order = orderWithStatus("in_transit");
        UUID providerId = UUID.randomUUID();
        order.setOrderingProviderId(providerId);
        when(labOrderRepository.findByIdAndTenantId(orderId, tenantId)).thenReturn(Optional.of(order));
        when(labOrderTestRepository.findById(testId)).thenReturn(Optional.of(testLine("CBC")));
        when(analyteResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AnalyteDefinition definition = definitionWithCritical("WBC", new BigDecimal("4.0"), new BigDecimal("11.0"), new BigDecimal("2.0"), new BigDecimal("30.0"));
        when(analyteDefinitionRepository.findByTenantIdAndTestCodeAndAnalyteName(tenantId, "CBC", "WBC")).thenReturn(Optional.of(definition));
        Provider provider = new Provider();
        provider.setId(providerId);
        provider.setAppUserId(null); // no linked login
        when(providerRepository.findByIdAndTenantId(providerId, tenantId)).thenReturn(Optional.of(provider));

        service.enterResults(orderId, testId, tenantId, List.of(new AnalyteResultInput("WBC", "35.0")));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void acknowledgingACriticalResultSucceedsAndIsIdempotent() {
        AnalyteResult result = new AnalyteResult();
        result.setId(UUID.randomUUID());
        result.setTenantId(tenantId);
        result.setFlag("critical");
        when(analyteResultRepository.findById(result.getId())).thenReturn(Optional.of(result));
        when(analyteResultRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID ackBy = UUID.randomUUID();

        AnalyteResult acknowledged = service.acknowledgeCritical(result.getId(), tenantId, ackBy);
        assertThat(acknowledged.getCriticalAcknowledgedBy()).isEqualTo(ackBy);
        assertThat(acknowledged.getCriticalAcknowledgedAt()).isNotNull();

        // Re-calling after it's already acknowledged doesn't overwrite or throw.
        var firstAckAt = acknowledged.getCriticalAcknowledgedAt();
        AnalyteResult reAcknowledged = service.acknowledgeCritical(result.getId(), tenantId, UUID.randomUUID());
        assertThat(reAcknowledged.getCriticalAcknowledgedAt()).isEqualTo(firstAckAt);
        assertThat(reAcknowledged.getCriticalAcknowledgedBy()).isEqualTo(ackBy);
    }

    @Test
    void acknowledgingANonCriticalResultIsRejected() {
        AnalyteResult result = new AnalyteResult();
        result.setId(UUID.randomUUID());
        result.setTenantId(tenantId);
        result.setFlag("normal");
        when(analyteResultRepository.findById(result.getId())).thenReturn(Optional.of(result));

        assertThatThrownBy(() -> service.acknowledgeCritical(result.getId(), tenantId, UUID.randomUUID()))
                .isInstanceOf(InvalidLabOrderStatusException.class);
    }

    private AnalyteDefinition definition(String name, BigDecimal low, BigDecimal high) {
        AnalyteDefinition definition = new AnalyteDefinition();
        definition.setId(UUID.randomUUID());
        definition.setTenantId(tenantId);
        definition.setAnalyteName(name);
        definition.setUnit("x10^9/L");
        definition.setNormalRangeLow(low);
        definition.setNormalRangeHigh(high);
        return definition;
    }

    private AnalyteDefinition definitionWithCritical(String name, BigDecimal normalLow, BigDecimal normalHigh, BigDecimal criticalLow, BigDecimal criticalHigh) {
        AnalyteDefinition definition = definition(name, normalLow, normalHigh);
        definition.setCriticalRangeLow(criticalLow);
        definition.setCriticalRangeHigh(criticalHigh);
        return definition;
    }
}
