package com.clinicops.laborder;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyteResultServiceTest {

    private final LabOrderRepository labOrderRepository = mock(LabOrderRepository.class);
    private final LabOrderTestRepository labOrderTestRepository = mock(LabOrderTestRepository.class);
    private final AnalyteDefinitionRepository analyteDefinitionRepository = mock(AnalyteDefinitionRepository.class);
    private final AnalyteResultRepository analyteResultRepository = mock(AnalyteResultRepository.class);
    private final AnalyteResultService service = new AnalyteResultService(
            labOrderRepository, labOrderTestRepository, analyteDefinitionRepository, analyteResultRepository);

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

        org.mockito.Mockito.verify(analyteResultRepository).deleteAllByLabOrderTestId(testId);
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
}
