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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Self-contained claims lifecycle - no real payer/clearinghouse
 * integration exists in this dev environment, same "mock/defer the real
 * vendor" call as {@link com.clinicops.paymentgateway.PaymentGatewayClient}
 * (phase 16). Bills one already-issued {@link Invoice} against one
 * {@link InsurancePolicy} - a plain reference, not a new Invoice/Payment
 * owner type.
 *
 * draft -> submitted -> (paid | partially_paid | denied) -> optionally
 * appealed (from denied, back into an adjudicatable state) -> closed (from
 * any status). Mirrors LabOrderStatusService's own conventions: re-calling
 * a transition already reached is idempotent; calling one out of order
 * throws {@link InvalidClaimStatusException}. {@link #submit} is the one
 * deliberate exception - like Specimen.sendToReferenceLab (phase L5), a
 * re-call while already submitted updates the claim number in place
 * rather than no-op'ing, since a payer's own claim number often isn't
 * known until after the first submission.
 */
@Service
public class ClaimService {

    private static final Set<String> ADJUDICATION_OUTCOMES = Set.of("paid", "partially_paid", "denied");

    private final ClaimRepository claimRepository;
    private final InvoiceRepository invoiceRepository;
    private final InsurancePolicyRepository insurancePolicyRepository;
    private final AppointmentRepository appointmentRepository;
    private final LabOrderRepository labOrderRepository;
    private final DispenseRecordRepository dispenseRecordRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final EncounterRepository encounterRepository;

    public ClaimService(
            ClaimRepository claimRepository,
            InvoiceRepository invoiceRepository,
            InsurancePolicyRepository insurancePolicyRepository,
            AppointmentRepository appointmentRepository,
            LabOrderRepository labOrderRepository,
            DispenseRecordRepository dispenseRecordRepository,
            PrescriptionRepository prescriptionRepository,
            EncounterRepository encounterRepository) {
        this.claimRepository = claimRepository;
        this.invoiceRepository = invoiceRepository;
        this.insurancePolicyRepository = insurancePolicyRepository;
        this.appointmentRepository = appointmentRepository;
        this.labOrderRepository = labOrderRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.encounterRepository = encounterRepository;
    }

    @Transactional
    public Claim createClaim(UUID invoiceId, UUID tenantId, CreateClaimRequest request, UUID createdBy) {
        Invoice invoice = invoiceRepository.findByIdAndTenantId(invoiceId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Invoice not found: " + invoiceId));
        InsurancePolicy policy = insurancePolicyRepository.findByIdAndTenantId(request.insurancePolicyId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Insurance policy not found: " + request.insurancePolicyId()));

        UUID patientId = resolvePatientId(invoice, tenantId);
        if (patientId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot file an insurance claim for an invoice with no patient on file (a guest/walk-in booking)");
        }
        if (!patientId.equals(policy.getPatientId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "insurancePolicyId does not belong to this invoice's own patient");
        }

        Claim claim = new Claim();
        claim.setTenantId(tenantId);
        claim.setInvoiceId(invoiceId);
        claim.setInsurancePolicyId(policy.getId());
        claim.setPatientId(patientId);
        claim.setNotes(request.notes());
        claim.setBilledAmount(invoice.getTotalAmount());
        claim.setCreatedBy(createdBy);
        return claimRepository.save(claim);
    }

    @Transactional
    public Claim submit(UUID claimId, UUID tenantId, SubmitClaimRequest request) {
        Claim claim = findOrThrow(claimId, tenantId);
        if ("submitted".equals(claim.getStatus())) {
            if (request.claimNumber() != null) {
                claim.setClaimNumber(request.claimNumber());
                touch(claim);
            }
            return claim;
        }
        if (!"draft".equals(claim.getStatus())) {
            throw new InvalidClaimStatusException("Cannot submit a claim with status '" + claim.getStatus() + "'");
        }
        claim.setStatus("submitted");
        claim.setClaimNumber(request.claimNumber());
        claim.setSubmittedAt(Instant.now());
        return touch(claim);
    }

    @Transactional
    public Claim recordAdjudication(UUID claimId, UUID tenantId, RecordAdjudicationRequest request) {
        Claim claim = findOrThrow(claimId, tenantId);
        if (!ADJUDICATION_OUTCOMES.contains(request.outcome())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome must be one of " + ADJUDICATION_OUTCOMES);
        }
        if (ADJUDICATION_OUTCOMES.contains(claim.getStatus())) {
            return claim;
        }
        if (!"submitted".equals(claim.getStatus()) && !"appealed".equals(claim.getStatus())) {
            throw new InvalidClaimStatusException("Cannot record adjudication for a claim with status '" + claim.getStatus() + "'");
        }

        if ("denied".equals(request.outcome())) {
            if (request.denialReason() == null || request.denialReason().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "denialReason is required when outcome is denied");
            }
            claim.setDenialReason(request.denialReason());
        } else {
            requireAmount(request.allowedAmount(), "allowedAmount");
            requireAmount(request.paidAmount(), "paidAmount");
            requireAmount(request.patientResponsibilityAmount(), "patientResponsibilityAmount");
            claim.setAllowedAmount(request.allowedAmount());
            claim.setPaidAmount(request.paidAmount());
            claim.setPatientResponsibilityAmount(request.patientResponsibilityAmount());
        }
        claim.setStatus(request.outcome());
        claim.setAdjudicatedAt(Instant.now());
        return touch(claim);
    }

    @Transactional
    public Claim appeal(UUID claimId, UUID tenantId, AppealClaimRequest request) {
        Claim claim = findOrThrow(claimId, tenantId);
        if ("appealed".equals(claim.getStatus())) {
            return claim;
        }
        if (!"denied".equals(claim.getStatus())) {
            throw new InvalidClaimStatusException("Cannot appeal a claim with status '" + claim.getStatus() + "'");
        }
        claim.setStatus("appealed");
        claim.setAppealReason(request.appealReason());
        claim.setAppealedAt(Instant.now());
        return touch(claim);
    }

    /** Callable from any status, including draft (abandoning a claim created by mistake) - terminal, idempotent on a re-call. */
    @Transactional
    public Claim close(UUID claimId, UUID tenantId, CloseClaimRequest request) {
        Claim claim = findOrThrow(claimId, tenantId);
        if ("closed".equals(claim.getStatus())) {
            return claim;
        }
        claim.setStatus("closed");
        if (request.notes() != null) {
            claim.setNotes(request.notes());
        }
        claim.setClosedAt(Instant.now());
        return touch(claim);
    }

    private void requireAmount(BigDecimal amount, String field) {
        if (amount == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required for this outcome");
        }
    }

    private Claim touch(Claim claim) {
        claim.setUpdatedAt(Instant.now());
        return claimRepository.save(claim);
    }

    private Claim findOrThrow(UUID id, UUID tenantId) {
        return claimRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Claim not found: " + id));
    }

    /** Same multi-hop resolution DispenseService.resolvePatientId already established for a dispense record; null for a guest-channel appointment/lab order or a prescription with no resolvable chain. */
    private UUID resolvePatientId(Invoice invoice, UUID tenantId) {
        if (invoice.getAppointmentId() != null) {
            return appointmentRepository.findByIdAndTenantId(invoice.getAppointmentId(), tenantId)
                    .map(Appointment::getPatientId).orElse(null);
        }
        if (invoice.getLabOrderId() != null) {
            return labOrderRepository.findByIdAndTenantId(invoice.getLabOrderId(), tenantId)
                    .map(LabOrder::getPatientId).orElse(null);
        }
        if (invoice.getDispenseRecordId() != null) {
            DispenseRecord record = dispenseRecordRepository.findByIdAndTenantId(invoice.getDispenseRecordId(), tenantId).orElse(null);
            if (record == null) {
                return null;
            }
            Prescription prescription = prescriptionRepository.findById(record.getPrescriptionId()).orElse(null);
            if (prescription == null) {
                return null;
            }
            Encounter encounter = encounterRepository.findById(prescription.getEncounterId()).orElse(null);
            if (encounter == null) {
                return null;
            }
            Appointment appointment = appointmentRepository.findById(encounter.getAppointmentId()).orElse(null);
            return appointment != null ? appointment.getPatientId() : null;
        }
        return null;
    }
}
