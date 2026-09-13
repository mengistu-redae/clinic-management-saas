package com.clinicops.laborder;

import com.clinicops.feepolicy.FeeCalculator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Mirrors CancellationService exactly. FeeCalculator.calculate(tenantId,
 * providerId, totalCost, dueAt) is reused completely unchanged, with
 * dueAt = Instant.now() (decided in plan mode - a lab order has no future
 * "due" instant the way an appointment has a slot start time, so this
 * deliberately always resolves against whichever fee_policies tier has
 * cutoffHours = 0, the same rows/config appointments already use).
 */
@Service
public class LabOrderCancellationService {

    private final LabOrderRepository labOrderRepository;
    private final LabOrderTestRepository labOrderTestRepository;
    private final FeeCalculator feeCalculator;
    private final LabOrderCancellationRepository labOrderCancellationRepository;

    public LabOrderCancellationService(
            LabOrderRepository labOrderRepository,
            LabOrderTestRepository labOrderTestRepository,
            FeeCalculator feeCalculator,
            LabOrderCancellationRepository labOrderCancellationRepository) {
        this.labOrderRepository = labOrderRepository;
        this.labOrderTestRepository = labOrderTestRepository;
        this.feeCalculator = feeCalculator;
        this.labOrderCancellationRepository = labOrderCancellationRepository;
    }

    @Transactional
    public LabOrderWithTests cancel(UUID id, UUID tenantId, UUID cancelledByUserId, String reason) {
        LabOrder order = labOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + id));

        if ("cancelled".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException("Lab order already cancelled: " + id);
        }
        if (!"ordered".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException(
                    "Cannot cancel a lab order with status '" + order.getStatus() + "' - only pre-collection orders can be cancelled");
        }

        BigDecimal totalCost = order.getTotalCost() != null ? order.getTotalCost() : BigDecimal.ZERO;
        BigDecimal feeAmount = feeCalculator.calculate(tenantId, order.getOrderingProviderId(), totalCost, Instant.now());

        order.setStatus("cancelled");
        order.setCancelledAt(Instant.now());
        order.setCancellationReason(reason);
        labOrderRepository.save(order);

        LabOrderCancellation cancellation = new LabOrderCancellation();
        cancellation.setTenantId(tenantId);
        cancellation.setLabOrderId(order.getId());
        cancellation.setCancelledBy(cancelledByUserId);
        cancellation.setReason(reason);
        cancellation.setFeeAmount(feeAmount);
        labOrderCancellationRepository.save(cancellation);

        List<LabOrderTest> tests = labOrderTestRepository.findAllByLabOrderId(order.getId());
        return new LabOrderWithTests(order, tests);
    }
}
