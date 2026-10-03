package com.clinicops.imaging;

import com.clinicops.notification.NotificationRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImagingOrderStatusServiceTest {

    private final ImagingOrderRepository imagingOrderRepository = mock(ImagingOrderRepository.class);
    private final ProviderRepository providerRepository = mock(ProviderRepository.class);
    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final ImagingOrderStatusService service = new ImagingOrderStatusService(
            imagingOrderRepository, providerRepository, appUserRepository, notificationRepository, new ObjectMapper());

    private final UUID tenantId = UUID.randomUUID();

    private ImagingOrder order(String status) {
        ImagingOrder order = new ImagingOrder();
        order.setId(UUID.randomUUID());
        order.setTenantId(tenantId);
        order.setOrderingProviderId(UUID.randomUUID());
        order.setOrderRef("ABC123");
        order.setStudyType("Chest X-ray");
        order.setStatus(status);
        when(imagingOrderRepository.findByIdAndTenantId(order.getId(), tenantId)).thenReturn(Optional.of(order));
        when(imagingOrderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return order;
    }

    @Test
    void scheduleTransitionsOrderedToScheduled() {
        ImagingOrder order = order("ordered");
        ImagingOrder result = service.schedule(order.getId(), tenantId, new ScheduleImagingOrderRequest(null));
        assertThat(result.getStatus()).isEqualTo("scheduled");
        assertThat(result.getScheduledAt()).isNotNull();
    }

    @Test
    void scheduleIsRejectedFromAnyOtherStatus() {
        ImagingOrder order = order("in_progress");
        org.junit.jupiter.api.Assertions.assertThrows(InvalidImagingOrderStatusException.class,
                () -> service.schedule(order.getId(), tenantId, new ScheduleImagingOrderRequest(null)));
    }

    @Test
    void startIsCallableFromEitherOrderedOrScheduled() {
        ImagingOrder ordered = order("ordered");
        assertThat(service.start(ordered.getId(), tenantId).getStatus()).isEqualTo("in_progress");

        ImagingOrder scheduled = order("scheduled");
        assertThat(service.start(scheduled.getId(), tenantId).getStatus()).isEqualTo("in_progress");
    }

    @Test
    void completeRequiresInProgress() {
        ImagingOrder order = order("scheduled");
        org.junit.jupiter.api.Assertions.assertThrows(InvalidImagingOrderStatusException.class,
                () -> service.complete(order.getId(), tenantId));
    }

    @Test
    void reviewWritesTheReportAndSignsOffInOneStep() {
        ImagingOrder order = order("completed");
        ImagingOrder result = service.review(order.getId(), tenantId, UUID.randomUUID(),
                new ReviewImagingOrderRequest("Clear lung fields", "No acute findings", false));
        assertThat(result.getStatus()).isEqualTo("reviewed");
        assertThat(result.getFindings()).isEqualTo("Clear lung fields");
        assertThat(result.getImpression()).isEqualTo("No acute findings");
        assertThat(result.isCriticalFinding()).isFalse();
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void reviewRejectedWhenNotYetCompleted() {
        ImagingOrder order = order("in_progress");
        org.junit.jupiter.api.Assertions.assertThrows(InvalidImagingOrderStatusException.class,
                () -> service.review(order.getId(), tenantId, UUID.randomUUID(),
                        new ReviewImagingOrderRequest("x", "y", false)));
    }

    @Test
    void reviewIsIdempotentOnceAlreadyReviewed() {
        ImagingOrder order = order("reviewed");
        order.setFindings("Original findings");
        ImagingOrder result = service.review(order.getId(), tenantId, UUID.randomUUID(),
                new ReviewImagingOrderRequest("Overwritten?", "Overwritten?", true));
        assertThat(result.getStatus()).isEqualTo("reviewed");
        assertThat(result.getFindings()).isEqualTo("Original findings");
        verify(imagingOrderRepository, never()).save(any());
    }

    @Test
    void criticalFindingNotifiesTheOrderingProviderWhenLinkedToARealLogin() {
        ImagingOrder order = order("completed");
        UUID appUserId = UUID.randomUUID();
        Provider provider = new Provider();
        provider.setId(order.getOrderingProviderId());
        provider.setTenantId(tenantId);
        provider.setAppUserId(appUserId);
        when(providerRepository.findByIdAndTenantId(order.getOrderingProviderId(), tenantId)).thenReturn(Optional.of(provider));
        AppUser appUser = new AppUser();
        appUser.setId(appUserId);
        appUser.setEmail("dr@example.test");
        when(appUserRepository.findById(appUserId)).thenReturn(Optional.of(appUser));

        service.review(order.getId(), tenantId, UUID.randomUUID(),
                new ReviewImagingOrderRequest("Mass noted", "Suspicious for malignancy", true));

        verify(notificationRepository).save(org.mockito.ArgumentMatchers.argThat(n ->
                n.getRecipient().equals("dr@example.test") && n.getType().equals("critical_imaging_finding")));
    }

    @Test
    void criticalFindingSkippedGracefullyWhenProviderHasNoLinkedLogin() {
        ImagingOrder order = order("completed");
        when(providerRepository.findByIdAndTenantId(order.getOrderingProviderId(), tenantId)).thenReturn(Optional.empty());

        service.review(order.getId(), tenantId, UUID.randomUUID(),
                new ReviewImagingOrderRequest("Mass noted", "Suspicious", true));

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void acknowledgeCriticalRejectsANonCriticalOrder() {
        ImagingOrder order = order("reviewed");
        order.setCriticalFinding(false);
        org.junit.jupiter.api.Assertions.assertThrows(InvalidImagingOrderStatusException.class,
                () -> service.acknowledgeCritical(order.getId(), tenantId, UUID.randomUUID()));
    }

    @Test
    void acknowledgeCriticalIsIdempotent() {
        ImagingOrder order = order("reviewed");
        order.setCriticalFinding(true);
        UUID firstAcknowledger = UUID.randomUUID();
        ImagingOrder first = service.acknowledgeCritical(order.getId(), tenantId, firstAcknowledger);
        assertThat(first.getCriticalAcknowledgedBy()).isEqualTo(firstAcknowledger);

        ImagingOrder second = service.acknowledgeCritical(order.getId(), tenantId, UUID.randomUUID());
        assertThat(second.getCriticalAcknowledgedBy()).isEqualTo(firstAcknowledger);
    }

    @Test
    void cancelIsPreStudyOnly() {
        ImagingOrder inProgress = order("in_progress");
        org.junit.jupiter.api.Assertions.assertThrows(InvalidImagingOrderStatusException.class,
                () -> service.cancel(inProgress.getId(), tenantId, new CancelImagingOrderRequest("test")));

        ImagingOrder ordered = order("ordered");
        ImagingOrder result = service.cancel(ordered.getId(), tenantId, new CancelImagingOrderRequest("Patient no-showed"));
        assertThat(result.getStatus()).isEqualTo("cancelled");
        assertThat(result.getCancellationReason()).isEqualTo("Patient no-showed");
    }
}
