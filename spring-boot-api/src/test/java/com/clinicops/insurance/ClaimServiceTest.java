package com.clinicops.insurance;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.invoice.Invoice;
import com.clinicops.invoice.InvoiceRepository;
import com.clinicops.laborder.LabOrder;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.pharmacy.DispenseRecord;
import com.clinicops.pharmacy.DispenseRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimServiceTest {

    private final ClaimRepository claimRepository = mock(ClaimRepository.class);
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final InsurancePolicyRepository insurancePolicyRepository = mock(InsurancePolicyRepository.class);
    private final AppointmentRepository appointmentRepository = mock(AppointmentRepository.class);
    private final LabOrderRepository labOrderRepository = mock(LabOrderRepository.class);
    private final DispenseRecordRepository dispenseRecordRepository = mock(DispenseRecordRepository.class);
    private final PrescriptionRepository prescriptionRepository = mock(PrescriptionRepository.class);
    private final EncounterRepository encounterRepository = mock(EncounterRepository.class);
    private final com.clinicops.imaging.ImagingOrderRepository imagingOrderRepository = mock(com.clinicops.imaging.ImagingOrderRepository.class);
    private final ClaimService service = new ClaimService(
            claimRepository, invoiceRepository, insurancePolicyRepository, appointmentRepository,
            labOrderRepository, dispenseRecordRepository, prescriptionRepository, encounterRepository, imagingOrderRepository);

    private final UUID tenantId = UUID.randomUUID();

    private Invoice appointmentInvoice(UUID patientId) {
        UUID appointmentId = UUID.randomUUID();
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setTenantId(tenantId);
        invoice.setAppointmentId(appointmentId);
        invoice.setTotalAmount(new BigDecimal("150.00"));

        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setTenantId(tenantId);
        appointment.setPatientId(patientId);
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        return invoice;
    }

    private Invoice labOrderInvoice(UUID patientId) {
        UUID labOrderId = UUID.randomUUID();
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setTenantId(tenantId);
        invoice.setLabOrderId(labOrderId);
        invoice.setTotalAmount(new BigDecimal("40.00"));

        LabOrder order = new LabOrder();
        order.setId(labOrderId);
        order.setTenantId(tenantId);
        order.setPatientId(patientId);
        when(labOrderRepository.findByIdAndTenantId(labOrderId, tenantId)).thenReturn(Optional.of(order));
        return invoice;
    }

    private Invoice dispenseInvoice(UUID patientId) {
        UUID dispenseRecordId = UUID.randomUUID();
        UUID prescriptionId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();

        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setTenantId(tenantId);
        invoice.setDispenseRecordId(dispenseRecordId);
        invoice.setTotalAmount(new BigDecimal("75.00"));

        DispenseRecord record = new DispenseRecord();
        record.setId(dispenseRecordId);
        record.setTenantId(tenantId);
        record.setPrescriptionId(prescriptionId);
        when(dispenseRecordRepository.findByIdAndTenantId(dispenseRecordId, tenantId)).thenReturn(Optional.of(record));

        Prescription prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setTenantId(tenantId);
        prescription.setEncounterId(encounterId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));

        Encounter encounter = new Encounter();
        encounter.setId(encounterId);
        encounter.setTenantId(tenantId);
        encounter.setAppointmentId(appointmentId);
        when(encounterRepository.findById(encounterId)).thenReturn(Optional.of(encounter));

        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setTenantId(tenantId);
        appointment.setPatientId(patientId);
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));

        return invoice;
    }

    private InsurancePolicy policy(UUID patientId) {
        InsurancePolicy policy = new InsurancePolicy();
        policy.setId(UUID.randomUUID());
        policy.setTenantId(tenantId);
        policy.setPatientId(patientId);
        return policy;
    }

    private Claim claim(String status) {
        Claim claim = new Claim();
        claim.setId(UUID.randomUUID());
        claim.setTenantId(tenantId);
        claim.setStatus(status);
        claim.setBilledAmount(new BigDecimal("100.00"));
        when(claimRepository.findByIdAndTenantId(claim.getId(), tenantId)).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return claim;
    }

    @Test
    void createClaimSnapshotsBilledAmountAndResolvesPatientThroughAppointment() {
        UUID patientId = UUID.randomUUID();
        Invoice invoice = appointmentInvoice(patientId);
        InsurancePolicy policy = policy(patientId);
        when(invoiceRepository.findByIdAndTenantId(invoice.getId(), tenantId)).thenReturn(Optional.of(invoice));
        when(insurancePolicyRepository.findByIdAndTenantId(policy.getId(), tenantId)).thenReturn(Optional.of(policy));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Claim result = service.createClaim(invoice.getId(), tenantId, new CreateClaimRequest(policy.getId(), null), UUID.randomUUID());

        assertThat(result.getStatus()).isEqualTo("draft");
        assertThat(result.getBilledAmount()).isEqualTo(new BigDecimal("150.00"));
        assertThat(result.getPatientId()).isEqualTo(patientId);
    }

    @Test
    void createClaimResolvesPatientThroughLabOrderAndThroughDispenseRecordChain() {
        UUID patientA = UUID.randomUUID();
        Invoice labInvoice = labOrderInvoice(patientA);
        InsurancePolicy policyA = policy(patientA);
        when(invoiceRepository.findByIdAndTenantId(labInvoice.getId(), tenantId)).thenReturn(Optional.of(labInvoice));
        when(insurancePolicyRepository.findByIdAndTenantId(policyA.getId(), tenantId)).thenReturn(Optional.of(policyA));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Claim fromLab = service.createClaim(labInvoice.getId(), tenantId, new CreateClaimRequest(policyA.getId(), null), UUID.randomUUID());
        assertThat(fromLab.getPatientId()).isEqualTo(patientA);

        UUID patientB = UUID.randomUUID();
        Invoice dispenseInvoiceRow = dispenseInvoice(patientB);
        InsurancePolicy policyB = policy(patientB);
        when(invoiceRepository.findByIdAndTenantId(dispenseInvoiceRow.getId(), tenantId)).thenReturn(Optional.of(dispenseInvoiceRow));
        when(insurancePolicyRepository.findByIdAndTenantId(policyB.getId(), tenantId)).thenReturn(Optional.of(policyB));
        Claim fromDispense = service.createClaim(
                dispenseInvoiceRow.getId(), tenantId, new CreateClaimRequest(policyB.getId(), null), UUID.randomUUID());
        assertThat(fromDispense.getPatientId()).isEqualTo(patientB);
    }

    @Test
    void createClaimRejectedWhenInvoiceHasNoResolvablePatient() {
        UUID appointmentId = UUID.randomUUID();
        Invoice invoice = new Invoice();
        invoice.setId(UUID.randomUUID());
        invoice.setTenantId(tenantId);
        invoice.setAppointmentId(appointmentId);
        invoice.setTotalAmount(BigDecimal.TEN);
        Appointment appointment = new Appointment();
        appointment.setId(appointmentId);
        appointment.setTenantId(tenantId);
        appointment.setPatientId(null);
        when(appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)).thenReturn(Optional.of(appointment));
        when(invoiceRepository.findByIdAndTenantId(invoice.getId(), tenantId)).thenReturn(Optional.of(invoice));
        InsurancePolicy policy = policy(UUID.randomUUID());
        when(insurancePolicyRepository.findByIdAndTenantId(policy.getId(), tenantId)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() ->
                service.createClaim(invoice.getId(), tenantId, new CreateClaimRequest(policy.getId(), null), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no patient on file");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void createClaimRejectedWhenPolicyBelongsToADifferentPatient() {
        UUID patientId = UUID.randomUUID();
        Invoice invoice = appointmentInvoice(patientId);
        InsurancePolicy otherPatientsPolicy = policy(UUID.randomUUID());
        when(invoiceRepository.findByIdAndTenantId(invoice.getId(), tenantId)).thenReturn(Optional.of(invoice));
        when(insurancePolicyRepository.findByIdAndTenantId(otherPatientsPolicy.getId(), tenantId)).thenReturn(Optional.of(otherPatientsPolicy));

        assertThatThrownBy(() -> service.createClaim(
                invoice.getId(), tenantId, new CreateClaimRequest(otherPatientsPolicy.getId(), null), UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not belong to this invoice's own patient");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void submitTransitionsDraftToSubmittedAndSetsClaimNumber() {
        Claim claim = claim("draft");
        Claim result = service.submit(claim.getId(), tenantId, new SubmitClaimRequest("CLM-1"));
        assertThat(result.getStatus()).isEqualTo("submitted");
        assertThat(result.getClaimNumber()).isEqualTo("CLM-1");
        assertThat(result.getSubmittedAt()).isNotNull();
    }

    @Test
    void reSubmittingAnAlreadySubmittedClaimUpdatesTheClaimNumberInPlaceRatherThanNoOpping() {
        Claim claim = claim("submitted");
        claim.setClaimNumber("OLD-NUMBER");
        var firstSubmittedAt = claim.getSubmittedAt();

        Claim result = service.submit(claim.getId(), tenantId, new SubmitClaimRequest("CORRECTED-NUMBER"));

        assertThat(result.getStatus()).isEqualTo("submitted");
        assertThat(result.getClaimNumber()).isEqualTo("CORRECTED-NUMBER");
        assertThat(result.getSubmittedAt()).isEqualTo(firstSubmittedAt);
    }

    @Test
    void submitRejectedFromAnAlreadyAdjudicatedStatus() {
        Claim claim = claim("paid");
        assertThatThrownBy(() -> service.submit(claim.getId(), tenantId, new SubmitClaimRequest(null)))
                .isInstanceOf(InvalidClaimStatusException.class);
    }

    @Test
    void recordAdjudicationPaidRequiresAllThreeAmounts() {
        Claim claim = claim("submitted");
        assertThatThrownBy(() -> service.recordAdjudication(
                claim.getId(), tenantId, new RecordAdjudicationRequest("paid", new BigDecimal("80"), null, new BigDecimal("20"), null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("paidAmount");
    }

    @Test
    void recordAdjudicationPaidSucceedsWithAllAmountsAndSetsAdjudicatedAt() {
        Claim claim = claim("submitted");
        Claim result = service.recordAdjudication(claim.getId(), tenantId,
                new RecordAdjudicationRequest("paid", new BigDecimal("80"), new BigDecimal("80"), BigDecimal.ZERO, null));
        assertThat(result.getStatus()).isEqualTo("paid");
        assertThat(result.getAllowedAmount()).isEqualTo(new BigDecimal("80"));
        assertThat(result.getAdjudicatedAt()).isNotNull();
    }

    @Test
    void recordAdjudicationDeniedRequiresADenialReason() {
        Claim claim = claim("submitted");
        assertThatThrownBy(() -> service.recordAdjudication(
                claim.getId(), tenantId, new RecordAdjudicationRequest("denied", null, null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("denialReason");
    }

    @Test
    void recordAdjudicationRejectedWhenClaimIsStillADraft() {
        Claim claim = claim("draft");
        assertThatThrownBy(() -> service.recordAdjudication(
                claim.getId(), tenantId, new RecordAdjudicationRequest("paid", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, null)))
                .isInstanceOf(InvalidClaimStatusException.class);
    }

    @Test
    void recordAdjudicationIsIdempotentOnceAlreadyAdjudicated() {
        Claim claim = claim("denied");
        claim.setDenialReason("Not covered");
        Claim result = service.recordAdjudication(
                claim.getId(), tenantId, new RecordAdjudicationRequest("paid", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, null));
        assertThat(result.getStatus()).isEqualTo("denied");
        assertThat(result.getDenialReason()).isEqualTo("Not covered");
        verify(claimRepository, never()).save(any());
    }

    @Test
    void appealTransitionsDeniedToAppealedAndCanBeReAdjudicated() {
        Claim claim = claim("denied");
        Claim appealed = service.appeal(claim.getId(), tenantId, new AppealClaimRequest("New documentation attached"));
        assertThat(appealed.getStatus()).isEqualTo("appealed");
        assertThat(appealed.getAppealedAt()).isNotNull();

        Claim reAdjudicated = service.recordAdjudication(claim.getId(), tenantId,
                new RecordAdjudicationRequest("partially_paid", new BigDecimal("50"), new BigDecimal("50"), new BigDecimal("50"), null));
        assertThat(reAdjudicated.getStatus()).isEqualTo("partially_paid");
    }

    @Test
    void appealRejectedWhenClaimWasNeverDenied() {
        Claim claim = claim("draft");
        assertThatThrownBy(() -> service.appeal(claim.getId(), tenantId, new AppealClaimRequest("reason")))
                .isInstanceOf(InvalidClaimStatusException.class);
    }

    @Test
    void closeIsCallableFromAnyStatusAndIdempotentOnceClosed() {
        Claim draft = claim("draft");
        Claim closedDraft = service.close(draft.getId(), tenantId, new CloseClaimRequest("Filed in error"));
        assertThat(closedDraft.getStatus()).isEqualTo("closed");
        assertThat(closedDraft.getNotes()).isEqualTo("Filed in error");

        Claim alreadyClosed = claim("closed");
        service.close(alreadyClosed.getId(), tenantId, new CloseClaimRequest("ignored"));
        verify(claimRepository, never()).save(alreadyClosed);
    }
}
