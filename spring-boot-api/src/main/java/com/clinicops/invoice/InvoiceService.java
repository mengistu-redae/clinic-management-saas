package com.clinicops.invoice;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.laborder.LabOrder;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.pharmacy.DispenseRecord;
import com.clinicops.pharmacy.DispenseRecordRepository;
import com.clinicops.pharmacy.Medication;
import com.clinicops.pharmacy.MedicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public InvoiceService(
            InvoiceRepository invoiceRepository,
            AppointmentRepository appointmentRepository,
            AppointmentTypeRepository appointmentTypeRepository,
            LabOrderRepository labOrderRepository,
            DispenseRecordRepository dispenseRecordRepository,
            MedicationRepository medicationRepository,
            ClinicSettingsService clinicSettingsService) {
        this.invoiceRepository = invoiceRepository;
        this.appointmentRepository = appointmentRepository;
        this.appointmentTypeRepository = appointmentTypeRepository;
        this.labOrderRepository = labOrderRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.medicationRepository = medicationRepository;
        this.clinicSettingsService = clinicSettingsService;
    }

    @Transactional
    public Invoice generateForAppointment(UUID appointmentId, UUID tenantId) {
        if (invoiceRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for appointment " + appointmentId);
        }
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
        AppointmentType type = appointmentTypeRepository.findByIdAndTenantId(appointment.getAppointmentTypeId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment type not found: " + appointment.getAppointmentTypeId()));

        Invoice invoice = build(tenantId, type.getPriceAmount());
        invoice.setAppointmentId(appointmentId);
        return invoiceRepository.save(invoice);
    }

    @Transactional
    public Invoice generateForLabOrder(UUID labOrderId, UUID tenantId) {
        if (invoiceRepository.findByLabOrderIdAndTenantId(labOrderId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for lab order " + labOrderId);
        }
        LabOrder order = labOrderRepository.findByIdAndTenantId(labOrderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + labOrderId));

        Invoice invoice = build(tenantId, order.getTotalCost() != null ? order.getTotalCost() : BigDecimal.ZERO);
        invoice.setLabOrderId(labOrderId);
        return invoiceRepository.save(invoice);
    }

    /**
     * Phase 31 - the subtotal is Medication.unitPrice * DispenseRecord
     * .quantityDispensed, the "computed suggestion" a future frontend
     * would pre-fill (a staff-editable amount still governs the actual
     * Payment, same as every other owner type).
     */
    @Transactional
    public Invoice generateForDispenseRecord(UUID dispenseRecordId, UUID tenantId) {
        if (invoiceRepository.findByDispenseRecordIdAndTenantId(dispenseRecordId, tenantId).isPresent()) {
            throw new InvoiceAlreadyExistsException("An invoice already exists for dispense record " + dispenseRecordId);
        }
        DispenseRecord record = dispenseRecordRepository.findByIdAndTenantId(dispenseRecordId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Dispense record not found: " + dispenseRecordId));
        Medication medication = medicationRepository.findByIdAndTenantId(record.getMedicationId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Medication not found: " + record.getMedicationId()));

        BigDecimal subtotal = medication.getUnitPrice().multiply(BigDecimal.valueOf(record.getQuantityDispensed()));
        Invoice invoice = build(tenantId, subtotal);
        invoice.setDispenseRecordId(dispenseRecordId);
        return invoiceRepository.save(invoice);
    }

    private Invoice build(UUID tenantId, BigDecimal subtotal) {
        BigDecimal taxRatePercent = clinicSettingsService.resolve(tenantId).taxRatePercent();
        BigDecimal taxAmount = subtotal
                .multiply(taxRatePercent)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        Invoice invoice = new Invoice();
        invoice.setTenantId(tenantId);
        invoice.setSubtotalAmount(subtotal);
        invoice.setTaxAmount(taxAmount);
        invoice.setTotalAmount(subtotal.add(taxAmount));
        return invoice;
    }
}
