package com.clinicops.laborder;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecimenServiceTest {

    private final SpecimenRepository specimenRepository = mock(SpecimenRepository.class);
    private final LabOrderTestRepository labOrderTestRepository = mock(LabOrderTestRepository.class);
    private final SpecimenService service = new SpecimenService(specimenRepository, labOrderTestRepository);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private LabOrderTest testLine(String specimenType) {
        LabOrderTest test = new LabOrderTest();
        test.setId(UUID.randomUUID());
        test.setTenantId(tenantId);
        test.setLabOrderId(orderId);
        test.setTestName("Test");
        test.setSpecimenType(specimenType);
        return test;
    }

    @Test
    void derivesOneSpecimenPerDistinctSpecimenType() {
        when(specimenRepository.save(any())).thenAnswer(inv -> {
            Specimen s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });

        LabOrderTest blood1 = testLine("blood");
        LabOrderTest blood2 = testLine("blood");
        LabOrderTest urine = testLine("urine");
        service.deriveForOrder(tenantId, orderId, List.of(blood1, blood2, urine));

        // Exactly 2 distinct specimens created - both blood tests share one.
        assertThat(blood1.getSpecimenId()).isNotNull();
        assertThat(blood1.getSpecimenId()).isEqualTo(blood2.getSpecimenId());
        assertThat(urine.getSpecimenId()).isNotNull();
        assertThat(urine.getSpecimenId()).isNotEqualTo(blood1.getSpecimenId());
    }

    @Test
    void testsWithNoSpecimenTypeAreNotLinkedToAnySpecimen() {
        LabOrderTest noType = testLine(null);
        LabOrderTest blank = testLine("  ");
        service.deriveForOrder(tenantId, orderId, List.of(noType, blank));

        assertThat(noType.getSpecimenId()).isNull();
        assertThat(blank.getSpecimenId()).isNull();
        verify(specimenRepository, never()).save(any());
        verify(labOrderTestRepository, never()).save(any());
    }

    @Test
    void deriveForOrderWipesAnyPreviouslyDerivedSpecimensFirst() {
        service.deriveForOrder(tenantId, orderId, List.of());
        verify(specimenRepository).deleteAllByLabOrderId(orderId);
    }

    @Test
    void collectingAPendingSpecimenSucceedsAndRecordsWhoAndWhen() {
        Specimen specimen = new Specimen();
        specimen.setId(UUID.randomUUID());
        specimen.setTenantId(tenantId);
        specimen.setStatus("pending_collection");
        when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));
        when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UUID collectedBy = UUID.randomUUID();

        Specimen result = service.collect(specimen.getId(), tenantId, collectedBy);

        assertThat(result.getStatus()).isEqualTo("collected");
        assertThat(result.getCollectedBy()).isEqualTo(collectedBy);
        assertThat(result.getCollectedAt()).isNotNull();
    }

    @Test
    void reCollectingAnAlreadyCollectedSpecimenIsIdempotent() {
        Specimen specimen = new Specimen();
        specimen.setId(UUID.randomUUID());
        specimen.setTenantId(tenantId);
        specimen.setStatus("collected");
        when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));

        service.collect(specimen.getId(), tenantId, UUID.randomUUID());

        verify(specimenRepository, never()).save(any());
    }

    @Test
    void collectingAnAlreadyRejectedSpecimenIsRejected() {
        Specimen specimen = new Specimen();
        specimen.setId(UUID.randomUUID());
        specimen.setTenantId(tenantId);
        specimen.setStatus("rejected");
        when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));

        assertThatThrownBy(() -> service.collect(specimen.getId(), tenantId, UUID.randomUUID()))
                .isInstanceOf(InvalidLabOrderStatusException.class);
    }

    @Test
    void completingASpecimenFromEitherReceivedOrProcessingSucceeds() {
        for (String fromStatus : List.of("received", "processing")) {
            Specimen specimen = new Specimen();
            specimen.setId(UUID.randomUUID());
            specimen.setTenantId(tenantId);
            specimen.setStatus(fromStatus);
            when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));
            when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Specimen result = service.complete(specimen.getId(), tenantId);

            assertThat(result.getStatus()).isEqualTo("completed");
            assertThat(result.getCompletedAt()).isNotNull();
        }
    }

    @Test
    void rejectingARequiresAReasonAndCannotRejectAnAlreadyCompletedSpecimen() {
        Specimen completed = new Specimen();
        completed.setId(UUID.randomUUID());
        completed.setTenantId(tenantId);
        completed.setStatus("completed");
        when(specimenRepository.findByIdAndTenantId(completed.getId(), tenantId)).thenReturn(Optional.of(completed));

        assertThatThrownBy(() -> service.reject(completed.getId(), tenantId, "too late"))
                .isInstanceOf(InvalidLabOrderStatusException.class);
    }

    @Test
    void collectAllForOrderOnlyAdvancesSpecimensStillPendingCollection() {
        Specimen pending = new Specimen();
        pending.setId(UUID.randomUUID());
        pending.setTenantId(tenantId);
        pending.setLabOrderId(orderId);
        pending.setStatus("pending_collection");
        Specimen alreadyCollected = new Specimen();
        alreadyCollected.setId(UUID.randomUUID());
        alreadyCollected.setTenantId(tenantId);
        alreadyCollected.setLabOrderId(orderId);
        alreadyCollected.setStatus("collected");

        when(specimenRepository.findAllByLabOrderId(orderId)).thenReturn(List.of(pending, alreadyCollected));
        when(specimenRepository.findByIdAndTenantId(pending.getId(), tenantId)).thenReturn(Optional.of(pending));
        when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.collectAllForOrder(tenantId, orderId, UUID.randomUUID());

        assertThat(pending.getStatus()).isEqualTo("collected");
        // The already-collected one was never re-looked-up by id - findByIdAndTenantId
        // was only ever stubbed/called for the pending one.
        verify(specimenRepository, never()).findByIdAndTenantId(alreadyCollected.getId(), tenantId);
    }

    @Test
    void sendingToAReferenceLabFromCollectedInTransitOrReceivedSucceeds() {
        for (String fromStatus : List.of("collected", "in_transit", "received")) {
            Specimen specimen = new Specimen();
            specimen.setId(UUID.randomUUID());
            specimen.setTenantId(tenantId);
            specimen.setStatus(fromStatus);
            when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));
            when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Specimen result = service.sendToReferenceLab(specimen.getId(), tenantId,
                    new SendToReferenceLabRequest("Outside Labs Inc", "REF-1", 5));

            assertThat(result.getStatus()).isEqualTo("sent_to_reference_lab");
            assertThat(result.getReferenceLabName()).isEqualTo("Outside Labs Inc");
            assertThat(result.getReferenceLabOrderNumber()).isEqualTo("REF-1");
            assertThat(result.getExpectedTurnaroundDays()).isEqualTo(5);
            assertThat(result.getSentToReferenceLabAt()).isNotNull();
        }
    }

    @Test
    void sendingToAReferenceLabFromPendingCollectionOrAfterCompletionIsRejected() {
        for (String fromStatus : List.of("pending_collection", "completed", "rejected")) {
            Specimen specimen = new Specimen();
            specimen.setId(UUID.randomUUID());
            specimen.setTenantId(tenantId);
            specimen.setStatus(fromStatus);
            when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));

            assertThatThrownBy(() -> service.sendToReferenceLab(specimen.getId(), tenantId,
                    new SendToReferenceLabRequest("Outside Labs Inc", null, null)))
                    .isInstanceOf(InvalidLabOrderStatusException.class);
        }
    }

    @Test
    void reSendingToAReferenceLabWhileAlreadySentUpdatesTheDetailsInPlace() {
        Specimen specimen = new Specimen();
        specimen.setId(UUID.randomUUID());
        specimen.setTenantId(tenantId);
        specimen.setStatus("sent_to_reference_lab");
        specimen.setReferenceLabName("Old Name");
        when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));
        when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Specimen result = service.sendToReferenceLab(specimen.getId(), tenantId,
                new SendToReferenceLabRequest("Corrected Name", "REF-2", 7));

        assertThat(result.getStatus()).isEqualTo("sent_to_reference_lab");
        assertThat(result.getReferenceLabName()).isEqualTo("Corrected Name");
        assertThat(result.getReferenceLabOrderNumber()).isEqualTo("REF-2");
        assertThat(result.getExpectedTurnaroundDays()).isEqualTo(7);
    }

    @Test
    void aSpecimenSentToAReferenceLabCanStillBeCompleted() {
        Specimen specimen = new Specimen();
        specimen.setId(UUID.randomUUID());
        specimen.setTenantId(tenantId);
        specimen.setStatus("sent_to_reference_lab");
        when(specimenRepository.findByIdAndTenantId(specimen.getId(), tenantId)).thenReturn(Optional.of(specimen));
        when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Specimen result = service.complete(specimen.getId(), tenantId);

        assertThat(result.getStatus()).isEqualTo("completed");
    }

    @Test
    void completeAllForOrderAlsoSweepsASpecimenSentToAReferenceLab() {
        Specimen sentOut = new Specimen();
        sentOut.setId(UUID.randomUUID());
        sentOut.setTenantId(tenantId);
        sentOut.setLabOrderId(orderId);
        sentOut.setStatus("sent_to_reference_lab");
        when(specimenRepository.findAllByLabOrderId(orderId)).thenReturn(List.of(sentOut));
        when(specimenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.completeAllForOrder(tenantId, orderId);

        assertThat(sentOut.getStatus()).isEqualTo("completed");
    }
}
