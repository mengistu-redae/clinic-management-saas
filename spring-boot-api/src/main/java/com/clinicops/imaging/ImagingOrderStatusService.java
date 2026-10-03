package com.clinicops.imaging;

import com.clinicops.notification.CriticalImagingFindingAlertPayload;
import com.clinicops.notification.Notification;
import com.clinicops.notification.NotificationPayloadWriter;
import com.clinicops.notification.NotificationRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * ordered -> scheduled -> in_progress -> completed -> reviewed, or
 * cancelled (ordered/scheduled only, pre-study). Mirrors
 * LabOrderStatusService's own conventions exactly: re-calling a
 * transition already reached is idempotent; calling one out of order
 * throws {@link InvalidImagingOrderStatusException}. `review` is the one
 * combined action that both writes the report and signs off (see
 * ImagingOrder's own javadoc for why) - firing a critical-finding alert
 * to the ordering provider the same way AnalyteResultService's own
 * critical-value alert does, the first time criticalFinding is set true
 * (not re-fired on an already-reviewed order, since review is terminal
 * here - no amendment path, matching this module's own "keep minimal"
 * scope boundary).
 */
@Service
public class ImagingOrderStatusService {

    private final ImagingOrderRepository imagingOrderRepository;
    private final ProviderRepository providerRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    public ImagingOrderStatusService(
            ImagingOrderRepository imagingOrderRepository,
            ProviderRepository providerRepository,
            AppUserRepository appUserRepository,
            NotificationRepository notificationRepository,
            ObjectMapper objectMapper) {
        this.imagingOrderRepository = imagingOrderRepository;
        this.providerRepository = providerRepository;
        this.appUserRepository = appUserRepository;
        this.notificationRepository = notificationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ImagingOrder schedule(UUID id, UUID tenantId, ScheduleImagingOrderRequest request) {
        ImagingOrder order = findOrThrow(id, tenantId);
        if ("scheduled".equals(order.getStatus())) {
            return order;
        }
        if (!"ordered".equals(order.getStatus())) {
            throw new InvalidImagingOrderStatusException("Cannot schedule an imaging order with status '" + order.getStatus() + "'");
        }
        order.setStatus("scheduled");
        order.setScheduledAt(request.scheduledAt() != null ? request.scheduledAt() : Instant.now());
        return imagingOrderRepository.save(order);
    }

    /** Callable from either "ordered" or "scheduled" - a walk-in study can skip formal scheduling. */
    @Transactional
    public ImagingOrder start(UUID id, UUID tenantId) {
        ImagingOrder order = findOrThrow(id, tenantId);
        if ("in_progress".equals(order.getStatus())) {
            return order;
        }
        if (!"ordered".equals(order.getStatus()) && !"scheduled".equals(order.getStatus())) {
            throw new InvalidImagingOrderStatusException("Cannot start an imaging order with status '" + order.getStatus() + "'");
        }
        order.setStatus("in_progress");
        order.setStartedAt(Instant.now());
        return imagingOrderRepository.save(order);
    }

    @Transactional
    public ImagingOrder complete(UUID id, UUID tenantId) {
        ImagingOrder order = findOrThrow(id, tenantId);
        if ("completed".equals(order.getStatus()) || "reviewed".equals(order.getStatus())) {
            return order;
        }
        if (!"in_progress".equals(order.getStatus())) {
            throw new InvalidImagingOrderStatusException("Cannot complete an imaging order with status '" + order.getStatus() + "'");
        }
        order.setStatus("completed");
        order.setCompletedAt(Instant.now());
        return imagingOrderRepository.save(order);
    }

    /** Writes the report and signs off in one step - completed -> reviewed. Re-calling once already reviewed is idempotent and does NOT overwrite the recorded findings. */
    @Transactional
    public ImagingOrder review(UUID id, UUID tenantId, UUID reviewedByUserId, ReviewImagingOrderRequest request) {
        ImagingOrder order = findOrThrow(id, tenantId);
        if ("reviewed".equals(order.getStatus())) {
            return order;
        }
        if (!"completed".equals(order.getStatus())) {
            throw new InvalidImagingOrderStatusException("Cannot review an imaging order with status '" + order.getStatus() + "'");
        }
        order.setFindings(request.findings());
        order.setImpression(request.impression());
        order.setCriticalFinding(request.criticalFinding());
        order.setStatus("reviewed");
        order.setReviewedAt(Instant.now());
        order.setReviewedBy(reviewedByUserId);
        ImagingOrder saved = imagingOrderRepository.save(order);
        if (request.criticalFinding()) {
            notifyCriticalFinding(saved, tenantId);
        }
        return saved;
    }

    /** Mirrors AnalyteResultService.acknowledgeCritical exactly - rejects a non-critical order, idempotent once already acknowledged. */
    @Transactional
    public ImagingOrder acknowledgeCritical(UUID id, UUID tenantId, UUID acknowledgedByUserId) {
        ImagingOrder order = findOrThrow(id, tenantId);
        if (!order.isCriticalFinding()) {
            throw new InvalidImagingOrderStatusException("Imaging order " + id + " has no critical finding to acknowledge");
        }
        if (order.getCriticalAcknowledgedAt() != null) {
            return order;
        }
        order.setCriticalAcknowledgedAt(Instant.now());
        order.setCriticalAcknowledgedBy(acknowledgedByUserId);
        return imagingOrderRepository.save(order);
    }

    /** Pre-study only - mirrors LabOrderCancellationService's own "before anything physical happened" gate, minus the fee-tier calculation (out of scope for this module's first pass). */
    @Transactional
    public ImagingOrder cancel(UUID id, UUID tenantId, CancelImagingOrderRequest request) {
        ImagingOrder order = findOrThrow(id, tenantId);
        if ("cancelled".equals(order.getStatus())) {
            return order;
        }
        if (!"ordered".equals(order.getStatus()) && !"scheduled".equals(order.getStatus())) {
            throw new InvalidImagingOrderStatusException("Cannot cancel an imaging order with status '" + order.getStatus() + "'");
        }
        order.setStatus("cancelled");
        order.setCancelledAt(Instant.now());
        order.setCancellationReason(request.reason());
        return imagingOrderRepository.save(order);
    }

    /** Skipped gracefully (no exception) when the ordering provider has no linked login or that login has no email - same precedent AnalyteResultService.notifyCriticalValue already established. */
    private void notifyCriticalFinding(ImagingOrder order, UUID tenantId) {
        Provider provider = providerRepository.findByIdAndTenantId(order.getOrderingProviderId(), tenantId).orElse(null);
        if (provider == null || provider.getAppUserId() == null) {
            return;
        }
        AppUser appUser = appUserRepository.findById(provider.getAppUserId()).orElse(null);
        if (appUser == null || appUser.getEmail() == null || appUser.getEmail().isBlank()) {
            return;
        }
        Notification notification = new Notification();
        notification.setTenantId(tenantId);
        notification.setRecipient(appUser.getEmail());
        notification.setType("critical_imaging_finding");
        notification.setPayload(NotificationPayloadWriter.toJson(objectMapper, new CriticalImagingFindingAlertPayload(order.getOrderRef(), order.getStudyType())));
        notificationRepository.save(notification);
    }

    private ImagingOrder findOrThrow(UUID id, UUID tenantId) {
        return imagingOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Imaging order not found: " + id));
    }
}
