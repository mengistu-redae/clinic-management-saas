package com.clinicops.laborder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Structured per-analyte result entry - a parallel, additive path alongside
 * LabOrderStatusService.result()'s own existing flat
 * result_value/result_unit/reference_range/abnormal_flag fields on
 * LabOrderTest, which stay completely unchanged and keep working for any
 * test, structured or not (see V31's own migration comment and the
 * CLAUDE.md phase L2 write-up for the full reasoning). This path doesn't
 * flip the owning LabOrder's own status - that's still
 * LabOrderStatusService.result()'s job, now callable with an empty
 * `results` list purely to mark an order resulted once every real value
 * has been entered here instead.
 */
@Service
public class AnalyteResultService {

    private static final Set<String> RESULT_ENTERABLE_STATUSES = Set.of("in_transit", "resulted", "reviewed");

    private final LabOrderRepository labOrderRepository;
    private final LabOrderTestRepository labOrderTestRepository;
    private final AnalyteDefinitionRepository analyteDefinitionRepository;
    private final AnalyteResultRepository analyteResultRepository;

    public AnalyteResultService(
            LabOrderRepository labOrderRepository,
            LabOrderTestRepository labOrderTestRepository,
            AnalyteDefinitionRepository analyteDefinitionRepository,
            AnalyteResultRepository analyteResultRepository) {
        this.labOrderRepository = labOrderRepository;
        this.labOrderTestRepository = labOrderTestRepository;
        this.analyteDefinitionRepository = analyteDefinitionRepository;
        this.analyteResultRepository = analyteResultRepository;
    }

    /** Full-replace on every call, same "replace the whole list" convention every other multi-row write in this codebase already uses. */
    @Transactional
    public List<AnalyteResult> enterResults(UUID orderId, UUID testId, UUID tenantId, List<AnalyteResultInput> inputs) {
        LabOrder order = requireOrder(orderId, tenantId);
        if (!RESULT_ENTERABLE_STATUSES.contains(order.getStatus())) {
            throw new InvalidLabOrderStatusException(
                    "Cannot enter analyte results for a lab order with status '" + order.getStatus() + "'");
        }
        LabOrderTest test = requireTest(testId, orderId, tenantId);

        analyteResultRepository.deleteAllByLabOrderTestId(testId);
        List<AnalyteResult> saved = new ArrayList<>();
        for (AnalyteResultInput input : inputs) {
            AnalyteDefinition definition = analyteDefinitionRepository
                    .findByTenantIdAndTestCodeAndAnalyteName(tenantId, test.getTestCode(), input.analyteName())
                    .orElse(null);

            AnalyteResult result = new AnalyteResult();
            result.setTenantId(tenantId);
            result.setLabOrderTestId(testId);
            result.setAnalyteName(input.analyteName());
            result.setValue(input.value());
            if (definition != null) {
                result.setAnalyteDefinitionId(definition.getId());
                result.setUnit(definition.getUnit());
                result.setReferenceRangeDisplay(referenceRangeDisplay(definition));
                result.setFlag(computeFlag(definition, input.value()));
            }
            saved.add(analyteResultRepository.save(result));
        }
        return saved;
    }

    public List<AnalyteResult> listForTest(UUID orderId, UUID testId, UUID tenantId) {
        requireOrder(orderId, tenantId);
        requireTest(testId, orderId, tenantId);
        return analyteResultRepository.findAllByLabOrderTestId(testId);
    }

    private static String referenceRangeDisplay(AnalyteDefinition definition) {
        if (definition.getNormalRangeLow() != null && definition.getNormalRangeHigh() != null) {
            return definition.getNormalRangeLow() + "-" + definition.getNormalRangeHigh();
        }
        return definition.getNormalRangeText();
    }

    /**
     * Normal/abnormal only when the definition has a real numeric range AND
     * the entered value itself parses as numeric - a non-numeric value
     * against a numeric range (or a definition with only normalRangeText)
     * is left "unflagged" rather than guessed at. True 3-way
     * normal/abnormal/critical flagging is L3's own job, once a critical
     * range exists alongside this normal one.
     */
    private static String computeFlag(AnalyteDefinition definition, String value) {
        if (definition.getNormalRangeLow() == null || definition.getNormalRangeHigh() == null) {
            return "unflagged";
        }
        try {
            BigDecimal numericValue = new BigDecimal(value.trim());
            boolean inRange = numericValue.compareTo(definition.getNormalRangeLow()) >= 0
                    && numericValue.compareTo(definition.getNormalRangeHigh()) <= 0;
            return inRange ? "normal" : "abnormal";
        } catch (NumberFormatException e) {
            return "unflagged";
        }
    }

    private LabOrder requireOrder(UUID orderId, UUID tenantId) {
        return labOrderRepository.findByIdAndTenantId(orderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + orderId));
    }

    private LabOrderTest requireTest(UUID testId, UUID orderId, UUID tenantId) {
        return labOrderTestRepository.findById(testId)
                .filter(t -> t.getTenantId().equals(tenantId) && t.getLabOrderId().equals(orderId))
                .orElseThrow(() -> new NoSuchElementException("Lab order test not found: " + testId));
    }
}
