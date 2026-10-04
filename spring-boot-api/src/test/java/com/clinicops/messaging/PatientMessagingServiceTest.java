package com.clinicops.messaging;

import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientProvisioningService;
import com.clinicops.patient.PatientRepository;
import com.clinicops.user.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PatientMessagingServiceTest {

    private final PatientMessageRepository patientMessageRepository = mock(PatientMessageRepository.class);
    private final PatientRepository patientRepository = mock(PatientRepository.class);
    private final PatientProvisioningService patientProvisioningService = mock(PatientProvisioningService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final PatientMessagingService service = new PatientMessagingService(
            patientMessageRepository, patientRepository, patientProvisioningService, currentUserService);

    private final UUID tenantId = UUID.randomUUID();
    private final Jwt jwt = mock(Jwt.class);

    @Test
    void sendAsPatientResolvesTheirOwnPatientRowAndSavesAPatientSentMessage() {
        UUID patientId = UUID.randomUUID();
        UUID senderUserId = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientProvisioningService.resolveForPortalUser(tenantId, jwt)).thenReturn(patient);
        when(currentUserService.resolveInternalUserId(jwt)).thenReturn(senderUserId);
        when(patientMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientMessage result = service.sendAsPatient(tenantId, "Hello, I have a question", jwt);

        assertThat(result.getPatientId()).isEqualTo(patientId);
        assertThat(result.getSenderType()).isEqualTo("patient");
        assertThat(result.getSenderUserId()).isEqualTo(senderUserId);
        assertThat(result.getBody()).isEqualTo("Hello, I have a question");
    }

    @Test
    void myMessagesMarksStaffSentMessagesReadAsASideEffect() {
        UUID patientId = UUID.randomUUID();
        Patient patient = new Patient();
        patient.setId(patientId);
        when(patientProvisioningService.resolveForPortalUser(tenantId, jwt)).thenReturn(patient);
        when(patientMessageRepository.findAllByPatientIdAndTenantIdOrderByCreatedAtAsc(patientId, tenantId)).thenReturn(List.of());

        service.myMessages(tenantId, jwt);

        verify(patientMessageRepository).markStaffMessagesReadByPatient(patientId, tenantId);
    }

    @Test
    void replyAsStaffRejectsAPatientNotBelongingToThisTenant() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.replyAsStaff(patientId, tenantId, "hi", jwt))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void replyAsStaffSavesAStaffSentMessage() {
        UUID patientId = UUID.randomUUID();
        UUID senderUserId = UUID.randomUUID();
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.of(new Patient()));
        when(currentUserService.resolveInternalUserId(jwt)).thenReturn(senderUserId);
        when(patientMessageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PatientMessage result = service.replyAsStaff(patientId, tenantId, "We can see you Tuesday", jwt);

        assertThat(result.getPatientId()).isEqualTo(patientId);
        assertThat(result.getSenderType()).isEqualTo("staff");
        assertThat(result.getSenderUserId()).isEqualTo(senderUserId);
    }

    @Test
    void threadForStaffMarksPatientSentMessagesReadAsASideEffect() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.of(new Patient()));
        when(patientMessageRepository.findAllByPatientIdAndTenantIdOrderByCreatedAtAsc(patientId, tenantId)).thenReturn(List.of());

        service.threadForStaff(patientId, tenantId);

        verify(patientMessageRepository).markPatientMessagesReadByStaff(patientId, tenantId);
    }

    @Test
    void threadForStaffRejectsAnUnownedPatient() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.findByIdAndTenantId(patientId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.threadForStaff(patientId, tenantId))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void inboxDelegatesDirectlyToTheRepository() {
        service.inbox(tenantId);
        verify(patientMessageRepository).findInbox(tenantId);
    }
}
