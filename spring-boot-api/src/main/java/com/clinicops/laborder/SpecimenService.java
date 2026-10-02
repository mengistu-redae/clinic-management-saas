package com.clinicops.laborder;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Derives Specimen rows from a LabOrder's own test lines and drives their
 * per-specimen status - see Specimen's own javadoc for why this is purely
 * additive alongside LabOrderStatusService, not a replacement for it.
 *
 * Mirrors LabOrderStatusService's own conventions exactly: re-calling a
 * transition already reached is idempotent, calling one out of order
 * throws InvalidLabOrderStatusException (reused as-is - same "wrong state
 * for this action" shape as the order-level version, just applied to a
 * Specimen instead of a LabOrder, not worth a parallel exception type for).
 */
@Service
public class SpecimenService {

    private final SpecimenRepository specimenRepository;
    private final LabOrderTestRepository labOrderTestRepository;

    public SpecimenService(SpecimenRepository specimenRepository, LabOrderTestRepository labOrderTestRepository) {
        this.specimenRepository = specimenRepository;
        this.labOrderTestRepository = labOrderTestRepository;
    }

    /**
     * Called whenever an order's own test lines are finalized (create,
     * confirm-and-order, or an update that replaces the test list) - wipes
     * any specimens already derived for this order and re-derives from
     * scratch, one Specimen per distinct non-blank specimenType among the
     * given tests, linking each test back to its own specimen. Safe to
     * re-run: the only time tests are ever replaced is while the order is
     * still in "ordered" status, before any real specimen work has started.
     */
    @Transactional
    public void deriveForOrder(UUID tenantId, UUID labOrderId, List<LabOrderTest> tests) {
        specimenRepository.deleteAllByLabOrderId(labOrderId);

        Map<String, Specimen> bySpecimenType = new LinkedHashMap<>();
        for (LabOrderTest test : tests) {
            String type = test.getSpecimenType();
            if (type == null || type.isBlank()) {
                continue;
            }
            Specimen specimen = bySpecimenType.computeIfAbsent(type, t -> {
                Specimen s = new Specimen();
                s.setTenantId(tenantId);
                s.setLabOrderId(labOrderId);
                s.setSpecimenType(t);
                return specimenRepository.save(s);
            });
            test.setSpecimenId(specimen.getId());
            labOrderTestRepository.save(test);
        }
    }

    public List<Specimen> listForOrder(UUID labOrderId) {
        return specimenRepository.findAllByLabOrderId(labOrderId);
    }

    // ---- individual specimen actions (the new fine-grained path a lab_technician uses directly) ----

    @Transactional
    public Specimen collect(UUID id, UUID tenantId, UUID collectedByUserId) {
        Specimen specimen = findOrThrow(id, tenantId);
        if ("collected".equals(specimen.getStatus())) {
            return specimen;
        }
        if (!"pending_collection".equals(specimen.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot collect a specimen with status '" + specimen.getStatus() + "'");
        }
        specimen.setStatus("collected");
        specimen.setCollectedAt(Instant.now());
        specimen.setCollectedBy(collectedByUserId);
        return specimenRepository.save(specimen);
    }

    @Transactional
    public Specimen markInTransit(UUID id, UUID tenantId) {
        Specimen specimen = findOrThrow(id, tenantId);
        if ("in_transit".equals(specimen.getStatus())) {
            return specimen;
        }
        if (!"collected".equals(specimen.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot mark in-transit a specimen with status '" + specimen.getStatus() + "'");
        }
        specimen.setStatus("in_transit");
        return specimenRepository.save(specimen);
    }

    @Transactional
    public Specimen receive(UUID id, UUID tenantId) {
        Specimen specimen = findOrThrow(id, tenantId);
        if ("received".equals(specimen.getStatus())) {
            return specimen;
        }
        if (!"in_transit".equals(specimen.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot receive a specimen with status '" + specimen.getStatus() + "'");
        }
        specimen.setStatus("received");
        specimen.setReceivedAt(Instant.now());
        return specimenRepository.save(specimen);
    }

    @Transactional
    public Specimen complete(UUID id, UUID tenantId) {
        Specimen specimen = findOrThrow(id, tenantId);
        if ("completed".equals(specimen.getStatus())) {
            return specimen;
        }
        if (!"received".equals(specimen.getStatus()) && !"processing".equals(specimen.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot complete a specimen with status '" + specimen.getStatus() + "'");
        }
        specimen.setStatus("completed");
        specimen.setCompletedAt(Instant.now());
        return specimenRepository.save(specimen);
    }

    /** No order-level equivalent triggers this - purely a new, specimen-level-only action (e.g. a hemolyzed sample needs recollection). */
    @Transactional
    public Specimen reject(UUID id, UUID tenantId, String reason) {
        Specimen specimen = findOrThrow(id, tenantId);
        if ("rejected".equals(specimen.getStatus())) {
            return specimen;
        }
        if ("completed".equals(specimen.getStatus())) {
            throw new InvalidLabOrderStatusException("Cannot reject an already-completed specimen");
        }
        specimen.setStatus("rejected");
        specimen.setRejectedAt(Instant.now());
        specimen.setRejectionReason(reason);
        return specimenRepository.save(specimen);
    }

    // ---- bulk-by-order actions, called from LabOrderStatusService so the two tracking layers stay in lockstep for the existing simple flow ----

    /** Every specimen still pending collection on this order becomes "collected" - the order-level collect-specimen action's own bulk equivalent. */
    @Transactional
    public void collectAllForOrder(UUID tenantId, UUID labOrderId, UUID collectedByUserId) {
        for (Specimen specimen : specimenRepository.findAllByLabOrderId(labOrderId)) {
            if ("pending_collection".equals(specimen.getStatus())) {
                collect(specimen.getId(), tenantId, collectedByUserId);
            }
        }
    }

    @Transactional
    public void markAllInTransitForOrder(UUID tenantId, UUID labOrderId) {
        for (Specimen specimen : specimenRepository.findAllByLabOrderId(labOrderId)) {
            if ("collected".equals(specimen.getStatus())) {
                markInTransit(specimen.getId(), tenantId);
            }
        }
    }

    /** The order-level result action's own bulk equivalent - once results are entered, every specimen that made it that far is done. */
    @Transactional
    public void completeAllForOrder(UUID tenantId, UUID labOrderId) {
        for (Specimen specimen : specimenRepository.findAllByLabOrderId(labOrderId)) {
            if ("in_transit".equals(specimen.getStatus()) || "received".equals(specimen.getStatus())
                    || "processing".equals(specimen.getStatus())) {
                // Skip markInTransit/receive's own guard chain - result entry can legitimately
                // follow "send" directly without every intermediate specimen-level step having
                // been individually walked through via the new fine-grained endpoints.
                specimen.setStatus("completed");
                specimen.setCompletedAt(Instant.now());
                specimenRepository.save(specimen);
            }
        }
    }

    private Specimen findOrThrow(UUID id, UUID tenantId) {
        return specimenRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Specimen not found: " + id));
    }
}
