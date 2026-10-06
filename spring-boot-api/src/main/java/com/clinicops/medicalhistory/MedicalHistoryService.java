package com.clinicops.medicalhistory;

import com.clinicops.patient.PatientRepository;
import com.clinicops.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * A single bean, plain @Transactional methods - same "not a contended
 * resource" reasoning as EncounterService/VitalsService, last-write-wins
 * is fine for a singleton-per-patient record like this.
 */
@Service
public class MedicalHistoryService {

    private final MedicalHistoryRepository medicalHistoryRepository;
    private final PatientRepository patientRepository;

    public MedicalHistoryService(MedicalHistoryRepository medicalHistoryRepository, PatientRepository patientRepository) {
        this.medicalHistoryRepository = medicalHistoryRepository;
        this.patientRepository = patientRepository;
    }

    /** Creates on first call, updates (full-replace) on every later call - patientId is the primary key itself, so there's exactly one row per patient either way, same shape as EncounterService/VitalsService's own upsert. */
    @Transactional
    public MedicalHistory upsert(UUID patientId, UUID tenantId, UUID recordedByUserId, UpsertMedicalHistoryRequest request) {
        requireOwnedPatient(patientId, tenantId);
        // Not ...AndTenantId - ownership is already proven via patientId
        // above (phase 45: the history may have first been recorded at a
        // sibling branch in the same clinic group); patientId is this
        // entity's own primary key, so findById IS the patient-scoped
        // lookup.
        MedicalHistory history = medicalHistoryRepository.findById(patientId)
                .orElseGet(() -> {
                    MedicalHistory fresh = new MedicalHistory();
                    fresh.setPatientId(patientId);
                    fresh.setTenantId(tenantId);
                    return fresh;
                });
        history.setPastConditions(request.pastConditions());
        history.setPastSurgeries(request.pastSurgeries());
        history.setCurrentMedications(request.currentMedications());
        history.setFamilyHistory(request.familyHistory());
        history.setSocialHistory(request.socialHistory());
        history.setRecordedBy(recordedByUserId);
        history.setUpdatedAt(Instant.now());
        return medicalHistoryRepository.save(history);
    }

    @Transactional(readOnly = true)
    public MedicalHistory get(UUID patientId, UUID tenantId) {
        requireOwnedPatient(patientId, tenantId);
        return medicalHistoryRepository.findById(patientId)
                .orElseThrow(() -> new NoSuchElementException("No medical history recorded yet for patient: " + patientId));
    }

    private void requireOwnedPatient(UUID patientId, UUID tenantId) {
        patientRepository.findAccessible(patientId, tenantId, TenantContext.clinicGroupId())
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + patientId));
    }
}
