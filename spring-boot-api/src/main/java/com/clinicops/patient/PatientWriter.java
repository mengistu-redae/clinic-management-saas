package com.clinicops.patient;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The write side of both patient registration (front-desk) and portal
 * auto-provisioning (see PatientProvisioningService) - a separate
 * {@code @Transactional} bean, same reasoning as AppUserWriter: a
 * same-class self-invocation would silently skip the transactional proxy.
 */
@Service
public class PatientWriter {

    private final PatientRepository patientRepository;

    public PatientWriter(PatientRepository patientRepository) {
        this.patientRepository = patientRepository;
    }

    @Transactional
    public Patient register(UUID tenantId, UUID clinicGroupId, CreatePatientRequest request) {
        Patient patient = new Patient();
        patient.setTenantId(tenantId);
        patient.setClinicGroupId(clinicGroupId);
        patient.setFirstName(request.firstName());
        patient.setLastName(request.lastName());
        patient.setDateOfBirth(request.dateOfBirth());
        patient.setPhone(request.phone());
        patient.setEmail(request.email());
        patient.setNationalId(request.nationalId());
        patient.setInsuranceMemberId(request.insuranceMemberId());
        return patientRepository.save(patient);
    }

    /**
     * Auto-provisions a minimal patient record for a portal login's first
     * booking at this clinic - see PatientProvisioningService. Only name/
     * email are known from the JWT; phone/DOB/national ID are left blank
     * for staff (or a future patient profile page) to fill in later.
     */
    @Transactional
    public Patient autoProvision(
            UUID tenantId, UUID clinicGroupId, UUID appUserId, String firstName, String lastName, String email) {
        Patient patient = new Patient();
        patient.setTenantId(tenantId);
        patient.setClinicGroupId(clinicGroupId);
        patient.setAppUserId(appUserId);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setEmail(email);
        try {
            return patientRepository.save(patient);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent first-booking requests for the same portal
            // user at the same clinic both missed the lookup and raced to
            // insert - idx_patients_tenant_app_user lets exactly one win.
            return patientRepository.findByTenantIdAndAppUserId(tenantId, appUserId).orElseThrow(() -> e);
        }
    }
}
