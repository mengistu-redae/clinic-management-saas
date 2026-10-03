package com.clinicops.imaging;

import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Order creation/editing - mirrors LabOrderService's own shape, minus the
 * multi-line-test pricing loop (one study per order, see ImagingOrder's
 * own javadoc) and the patient-initiated request flow (not mirrored here).
 * Status transitions live in ImagingOrderStatusService, same separation
 * LabOrderStatusService already keeps from LabOrderService.
 */
@Service
public class ImagingOrderService {

    private final ImagingOrderRepository imagingOrderRepository;
    private final ImagingStudyRateRepository imagingStudyRateRepository;
    private final PatientRepository patientRepository;
    private final ProviderRepository providerRepository;
    private final ClinicRepository clinicRepository;
    private final ImagingOrderRefGenerator refGenerator;

    public ImagingOrderService(
            ImagingOrderRepository imagingOrderRepository,
            ImagingStudyRateRepository imagingStudyRateRepository,
            PatientRepository patientRepository,
            ProviderRepository providerRepository,
            ClinicRepository clinicRepository,
            ImagingOrderRefGenerator refGenerator) {
        this.imagingOrderRepository = imagingOrderRepository;
        this.imagingStudyRateRepository = imagingStudyRateRepository;
        this.patientRepository = patientRepository;
        this.providerRepository = providerRepository;
        this.clinicRepository = clinicRepository;
        this.refGenerator = refGenerator;
    }

    @Transactional
    public ImagingOrder create(UUID tenantId, CreateImagingOrderRequest request) {
        patientRepository.findByIdAndTenantId(request.patientId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Patient not found: " + request.patientId()));
        providerRepository.findByIdAndTenantId(request.orderingProviderId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Provider not found: " + request.orderingProviderId()));

        ImagingOrder order = new ImagingOrder();
        order.setTenantId(tenantId);
        order.setPatientId(request.patientId());
        order.setOrderingProviderId(request.orderingProviderId());
        order.setModality(request.modality());
        order.setStudyType(request.studyType());
        order.setPriority(request.priority() != null && !request.priority().isBlank() ? request.priority() : "routine");
        order.setNotes(request.notes());
        order.setStatus("ordered");
        order.setOrderRef(refGenerator.nextOrderRef());
        order.setClinicRef(refGenerator.nextClinicRef(tenantId, clinicName(tenantId)));
        applyPricing(order, tenantId, request.studyCode());
        return imagingOrderRepository.save(order);
    }

    public ImagingOrder get(UUID id, UUID tenantId) {
        return imagingOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Imaging order not found: " + id));
    }

    public List<ImagingOrder> listForTenant(UUID tenantId) {
        return imagingOrderRepository.findAllByTenantId(tenantId);
    }

    /** Partial update - clinical fields only while status = "ordered", mirrors LabOrderService.update's own gate. */
    @Transactional
    public ImagingOrder update(UUID id, UUID tenantId, UpdateImagingOrderRequest request) {
        ImagingOrder order = imagingOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Imaging order not found: " + id));
        if (!"ordered".equals(order.getStatus())) {
            throw new InvalidImagingOrderStatusException(
                    "Cannot edit an imaging order with status '" + order.getStatus() + "' - only 'ordered' allows clinical edits");
        }

        if (request.orderingProviderId() != null) {
            providerRepository.findByIdAndTenantId(request.orderingProviderId(), tenantId)
                    .orElseThrow(() -> new NoSuchElementException("Provider not found: " + request.orderingProviderId()));
            order.setOrderingProviderId(request.orderingProviderId());
        }
        if (request.modality() != null) {
            order.setModality(request.modality());
        }
        if (request.studyType() != null) {
            order.setStudyType(request.studyType());
        }
        if (request.priority() != null) {
            order.setPriority(request.priority());
        }
        if (request.notes() != null) {
            order.setNotes(request.notes());
        }
        if (request.studyCode() != null) {
            applyPricing(order, tenantId, request.studyCode());
        }
        return imagingOrderRepository.save(order);
    }

    private void applyPricing(ImagingOrder order, UUID tenantId, String studyCode) {
        ImagingStudyRate rate = imagingStudyRateRepository.findByTenantIdAndStudyCode(tenantId, studyCode)
                .orElseThrow(() -> new NoImagingRateConfiguredException("No imaging study rate configured for study code: " + studyCode));
        order.setStudyCode(studyCode);
        order.setTotalCost(rate.getBaseCharge());
    }

    private String clinicName(UUID tenantId) {
        return clinicRepository.findById(tenantId).map(Clinic::getName).orElse(null);
    }
}
