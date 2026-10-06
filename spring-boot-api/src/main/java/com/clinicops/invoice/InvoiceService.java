package com.clinicops.invoice;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.imaging.ImagingOrder;
import com.clinicops.imaging.ImagingOrderRepository;
import com.clinicops.laborder.LabOrder;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.pharmacy.DispenseRecord;
import com.clinicops.pharmacy.DispenseRecordRepository;
import com.clinicops.pharmacy.Medication;
import com.clinicops.pharmacy.MedicationRepository;
import com.clinicops.payment.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The first real consumer of ClinicSettings.taxRatePercent - dormant since
 * phase 5. A dedicated bean (not plain controller-calls-repository CRUD)
 * because generating an invoice genuinely spans several other beans
 * (AppointmentType/LabOrder pricing, ClinicSettingsService.resolve), same
 * "cross-cutting dependency" reasoning that justified ClinicSettingsService
 * itself back in phase 5.
 *
 * No status gate on either owner (matches the "reschedule/cancel guards
 * stay minimal" convention) - staff decide when to bill, not the state
 * machine. An invoice is an immutable financial record: generating a
 * second one for the same owner is rejected (409), never a full-replace,
 * so an already-issued invoice's amounts can never silently change under
 * it (unlike Encounter's own upsert-in-place, which is deliberately the
 * opposite because clinical notes evolve).
 */
@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final AppointmentRepository appointmentRepository;
    private final AppointmentTypeRepository appointmentTypeRepository;
    private final LabOrderRepository labOrderRepository;
    private final DispenseRecordRepository dispenseRecordRepository;
    private final MedicationRepository medicationRepository;
    private final ClinicSettingsService clinicSettingsService;
    private final ImagingOrderRepository imagingOrderRepository;
    private final PaymentRepository paymentRepository;

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            AppointmentRepository appointmentRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            LabOrderRepository labOrderRepository,
            DispenseRecordRepository dispenseRecordRepository,
            MedicationRepository medicationRepository,
            ClinicSettingsService clinicSettingsService,
            ImagingOrderRepository imagingOrderRepository,
            PaymentRepository paymentRepository) {
        this.invoiceRepository = invoiceRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.labOrderRepository = labOrderRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.medicationRepository = medicationRepository;
        this.clinicSettingsService = clinicSettingsService;
        this.imagingOrderRepository = imagingOrderRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional
    public Invoice generateForAppointment(UUID appointmentId, UUID tenantId, GenerateInvoiceRequest request) {
        if (invoiceRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for appointment " + appointmentId);
        }
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        AppointmentType type = appointmentTypeRepository.findByIdAndTenantId(appointment.getAppointmentTypeId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment type not found: " + appointment.getAppointmentTypeId()));

        Invoice invoice = build(tenantId, type.getPriceAmount(), request);
        invoice.setAppointmentId(appointmentId);
        invoice = invoiceRepository.save(invoice);
        paymentRepository.linkUnlinkedPaymentsForAppointment(tenantId, appointmentId, invoice.getId());
        return invoice;
    }

    @Transactional
    public Invoice generateForLabOrder(UUID labOrderId, UUID tenantId, GenerateInvoiceRequest request) {
        if (invoiceRepository.findByLabOrderIdAndTenantId(labOrderId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for lab order " + labOrderId);
        }
        LabOrder order = labOrderRepository.findByIdAndTenantId(labOrderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + labOrderId));

        Invoice invoice = build(tenantId, order.getTotalCost() != null ? order.getTotalCost() : BigDecimal.ZERO, request);
        invoice.setLabOrderId(labOrderId);
        invoice = invoiceRepository.save(invoice);
        paymentRepository.linkUnlinkedPaymentsForLabOrder(tenantId, labOrderId, invoice.getId());
        return invoice;
    }

    /**
     * Phase 31 - the subtotal is Medication.unitPrice * DispenseRecord
     * .quantityDispensed, the "computed suggestion" a future frontend
     * would pre-fill (a staff-editable amount still governs the actual
     * Payment, same as every other owner type).
     */
    @Transactional
    public Invoice generateForDispenseRecord(UUID dispenseRecordId, UUID tenantId, GenerateInvoiceRequest request) {
        if (invoiceRepository.findByDispenseRecordIdAndTenantId(dispenseRecordId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for dispense record " + dispenseRecordId);
        }
        DispenseRecord record = dispenseRecordRepository.findByIdAndTenantId(dispenseRecordId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Dispense record not found: " + dispenseRecordId));
        Medication medication = medicationRepository.findByIdAndTenantId(record.getMedicationId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + record.getMedicationId()));

        BigDecimal subtotal = medication.getUnitPrice().multiply(BigDecimal.valueOf(record.getQuantityDispensed()));
        Invoice invoice = build(tenantId, subtotal, request);
        invoice.setDispenseRecordId(dispenseRecordId);
        invoice = invoiceRepository.save(invoice);
        paymentRepository.linkUnlinkedPaymentsForDispenseRecord(tenantId, dispenseRecordId, invoice.getId());
        return invoice;
    }

    @Transactional
    public Invoice generateForImagingOrder(UUID imagingOrderId, UUID tenantId, GenerateInvoiceRequest request) {
        if (invoiceRepository.findByImagingOrderIdAndTenantId(imagingOrderId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for imaging order " + imagingOrderId);
        }
        ImagingOrder order = imagingOrderRepository.findByIdAndTenantId(imagingOrderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Imaging order not found: " + imagingOrderId));

        Invoice invoice = build(tenantId, order.getTotalCost() != null ? order.getTotalCost() : BigDecimal.ZERO, request);
        invoice.setImagingOrderId(imagingOrderId);
        invoice = invoiceRepository.save(invoice);
        paymentRepository.linkUnlinkedPaymentsForImagingOrder(tenantId, imagingOrderId, invoice.getId());
        return invoice;
    }

    /**
     * Phase 46 - discount is applied to the subtotal before tax (tax is
     * charged on the discounted price, standard retail convention). At
     * most one of discountPercent/discountAmount may be given - a
     * validation annotation alone can't express that, same shape
     * ReferralService's own internal-xor-external check already uses.
     */
    private Invoice build(UUID tenantId, BigDecimal subtotal, GenerateInvoiceRequest request) {
        BigDecimal discountPercent = request != null ? request.discountPercent() : null;
        BigDecimal requestedDiscountAmount = request != null ? request.discountAmount() : null;
        if (discountPercent != null && requestedDiscountAmount != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give at most one of discountPercent or discountAmount");
        }

        BigDecimal discountAmount = BigDecimal.ZERO;
        if (discountPercent != null) {
            discountAmount = subtotal
                    .multiply(discountPercent)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else if (requestedDiscountAmount != null) {
            discountAmount = requestedDiscountAmount;
        }
        if (discountAmount.compareTo(subtotal) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "discountAmount cannot exceed the subtotal");
        }

        BigDecimal taxRatePercent = clinicSettingsService.resolve(tenantId).taxRatePercent();
        BigDecimal discountedSubtotal = subtotal.subtract(discountAmount);
        BigDecimal taxAmount = discountedSubtotal
                .multiply(taxRatePercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Invoice invoice = new Invoice();
        invoice.setTenantId(tenantId);
        invoice.setSubtotalAmount(subtotal);
        invoice.setDiscountAmount(discountAmount);
        invoice.setDiscountReason(request != null ? request.discountReason() : null);
        invoice.setTaxAmount(taxAmount);
        invoice.setTotalAmount(discountedSubtotal.add(taxAmount));
        return invoice;
    }
}
