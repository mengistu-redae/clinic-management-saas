package com.clinicops.messaging;

import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientProvisioningService;
import com.clinicops.patient.PatientRepository;
import com.clinicops.user.CurrentUserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Both sides of the shared clinic inbox (the user's own pinned decision -
 * no patient-picks-a-provider step, no per-topic threads). Sending never
 * marks anything read; only *viewing* a thread does, mirroring an ordinary
 * messaging app and matching this project's own "a read is a side effect
 * of a GET, not a separate endpoint" convention nowhere else explicit but
 * implied by how little this app otherwise tracks read state.
 */
@Service
public class PatientMessagingService {

    private final PatientMessageRepository patientMessageRepository;
    private final PatientRepository patientRepository;
    private final PatientProvisioningService patientProvisioningService;
    private final CurrentUserService currentUserService;

    public PatientMessagingService(
            PatientMessageRepository patientMessageRepository,
            PatientRepository patientRepository,
            PatientProvisioningService patientProvisioningService,
            CurrentUserService currentUserService) {
        this.patientMessageRepository = patientMessageRepository;
        this.patientRepository = patientRepository;
        this.patientProvisioningService = patientProvisioningService;
        this.currentUserService = currentUserService;
    }

    @Transactional
    public PatientMessage sendAsPatient(UUID clinicId, String body, Jwt jwt) {
        Patient patient = patientProvisioningService.resolveForPortalUser(clinicId, jwt);
        return save(clinicId, patient.getId(), "patient", currentUserService.resolveInternalUserId(jwt), body);
    }

    /** Auto-provisioning on a bare view (same as sendAsPatient) is harmless - an empty thread for a brand-new patient is indistinguishable either way. */
    @Transactional
    public List<PatientMessage> myMessages(UUID clinicId, Jwt jwt) {
        Patient patient = patientProvisioningService.resolveForPortalUser(clinicId, jwt);
        List<PatientMessage> messages = patientMessageRepository.findAllByPatientIdAndTenantIdOrderByCreatedAtAsc(patient.getId(), clinicId);
        patientMessageRepository.markStaffMessagesReadByPatient(patient.getId(), clinicId);
        return messages;
    }

    @Transactional
    public PatientMessage replyAsStaff(UUID patientId, UUID tenantId, String body, Jwt jwt) {
        requireOwnedPatient(patientId, tenantId);
        return save(tenantId, patientId, "staff", currentUserService.resolveInternalUserId(jwt), body);
    }

    @Transactional
    public List<PatientMessage> threadForStaff(UUID patientId, UUID tenantId) {
        requireOwnedPatient(patientId, tenantId);
        List<PatientMessage> messages = patientMessageRepository.findAllByPatientIdAndTenantIdOrderByCreatedAtAsc(patientId, tenantId);
        patientMessageRepository.markPatientMessagesReadByStaff(patientId, tenantId);
        return messages;
    }

    public List<MessageInboxEntry> inbox(UUID tenantId) {
        return patientMessageRepository.findInbox(tenantId);
    }

    private void requireOwnedPatient(UUID patientId, UUID tenantId) {
        patientRepository.findByIdAndTenantId(patientId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
    }

    private PatientMessage save(UUID tenantId, UUID patientId, String senderType, UUID senderUserId, String body) {
        PatientMessage message = new PatientMessage();
        message.setTenantId(tenantId);
        message.setPatientId(patientId);
        message.setSenderType(senderType);
        message.setSenderUserId(senderUserId);
        message.setBody(body);
        return patientMessageRepository.save(message);
    }
}
