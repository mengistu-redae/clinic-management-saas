package com.clinicops.invoice;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.clinicsettings.ClinicBrandingView;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.clinicsettings.EffectiveClinicSettings;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.imaging.ImagingOrder;
import com.clinicops.imaging.ImagingOrderRepository;
import com.clinicops.laborder.LabOrder;
import com.clinicops.laborder.LabOrderRepository;
import com.clinicops.patient.PatientRepository;
import com.clinicops.pharmacy.DispenseRecord;
import com.clinicops.pharmacy.DispenseRecordRepository;
import com.clinicops.pharmacy.Medication;
import com.clinicops.pharmacy.MedicationRepository;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Renders straight from the already-issued (immutable) Invoice row plus
 * clinic branding for a letterhead - nothing to gain from persisting bytes
 * for something regenerable at zero cost from data that can't change
 * (Invoice has no update endpoint at all), and it avoids adding a second
 * consumer of the phase-13 uploads volume for an unrelated purpose. Never
 * called with an owner mismatch - both callers (AppointmentInvoiceController/
 * LabOrderInvoiceController) already resolved the Invoice via their own
 * tenant-scoped lookup before reaching here.
 */
@Service
public class InvoicePdfService {

    private static final DateTimeFormatter ISSUED_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneOffset.UTC);

    private final ClinicSettingsService clinicSettingsService;
    private final AppointmentRepository appointmentRepository;
    private final LabOrderRepository labOrderRepository;
    private final PatientRepository patientRepository;
    private final DispenseRecordRepository dispenseRecordRepository;
    private final MedicationRepository medicationRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final EncounterRepository encounterRepository;
    private final ImagingOrderRepository imagingOrderRepository;

    public InvoicePdfService(
            ClinicSettingsService clinicSettingsService,
            AppointmentRepository appointmentRepository,
            LabOrderRepository labOrderRepository,
            PatientRepository patientRepository,
            DispenseRecordRepository dispenseRecordRepository,
            MedicationRepository medicationRepository,
            PrescriptionRepository prescriptionRepository,
            EncounterRepository encounterRepository,
            ImagingOrderRepository imagingOrderRepository) {
        this.clinicSettingsService = clinicSettingsService;
        this.appointmentRepository = appointmentRepository;
        this.labOrderRepository = labOrderRepository;
        this.patientRepository = patientRepository;
        this.dispenseRecordRepository = dispenseRecordRepository;
        this.medicationRepository = medicationRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.encounterRepository = encounterRepository;
        this.imagingOrderRepository = imagingOrderRepository;
    }

    public byte[] renderForAppointment(Invoice invoice) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(invoice.getAppointmentId(), invoice.getTenantId()).orElse(null);
        String ownerReference = appointment != null ? "Appointment " + appointment.getAppointmentRef() : null;
        String patientName = appointment != null ? resolvePatientName(appointment.getPatientId(), invoice.getTenantId(), appointment.getContactName()) : null;
        return render(invoice, ownerReference, patientName);
    }

    public byte[] renderForLabOrder(Invoice invoice) {
        LabOrder order = labOrderRepository.findByIdAndTenantId(invoice.getLabOrderId(), invoice.getTenantId()).orElse(null);
        String ownerReference = order != null ? "Lab Order " + order.getOrderRef() : null;
        String patientName = order != null ? resolvePatientName(order.getPatientId(), invoice.getTenantId(), null) : null;
        return render(invoice, ownerReference, patientName);
    }

    /**
     * Same multi-hop chain DispenseService.resolvePatientId already
     * established (Prescription -> Encounter -> Appointment ->
     * patientId), needed here only for display - falls back to the
     * appointment's own contactName for a guest, same as
     * renderForAppointment.
     */
    public byte[] renderForDispenseRecord(Invoice invoice) {
        DispenseRecord record = dispenseRecordRepository.findByIdAndTenantId(invoice.getDispenseRecordId(), invoice.getTenantId()).orElse(null);
        Medication medication = record != null
                ? medicationRepository.findByIdAndTenantId(record.getMedicationId(), invoice.getTenantId()).orElse(null)
                : null;
        String ownerReference = medication != null
                ? "Dispense of " + medication.getName() + " ×" + record.getQuantityDispensed()
                : null;
        String patientName = record != null ? resolveDispensePatientName(record, invoice.getTenantId()) : null;
        return render(invoice, ownerReference, patientName);
    }

    public byte[] renderForImagingOrder(Invoice invoice) {
        ImagingOrder order = imagingOrderRepository.findByIdAndTenantId(invoice.getImagingOrderId(), invoice.getTenantId()).orElse(null);
        String ownerReference = order != null ? "Imaging Order " + order.getOrderRef() : null;
        String patientName = order != null ? resolvePatientName(order.getPatientId(), invoice.getTenantId(), null) : null;
        return render(invoice, ownerReference, patientName);
    }

    private String resolveDispensePatientName(DispenseRecord record, UUID tenantId) {
        Prescription prescription = prescriptionRepository.findById(record.getPrescriptionId()).orElse(null);
        if (prescription == null) {
            return null;
        }
        Encounter encounter = encounterRepository.findById(prescription.getEncounterId()).orElse(null);
        if (encounter == null) {
            return null;
        }
        Appointment appointment = appointmentRepository.findByIdAndTenantId(encounter.getAppointmentId(), tenantId).orElse(null);
        if (appointment == null) {
            return null;
        }
        return resolvePatientName(appointment.getPatientId(), tenantId, appointment.getContactName());
    }

    private String resolvePatientName(UUID patientId, UUID tenantId, String guestContactName) {
        if (patientId != null) {
            return patientRepository.findByIdAndTenantId(patientId, tenantId)
                    .map(p -> p.getFirstName() + " " + p.getLastName())
                    .orElse(guestContactName);
        }
        return guestContactName;
    }

    private byte[] render(Invoice invoice, String ownerReference, String patientName) {
        ClinicBrandingView branding = clinicSettingsService.getBranding(invoice.getTenantId());
        EffectiveClinicSettings settings = clinicSettingsService.resolve(invoice.getTenantId());

        Font clinicNameFont = new Font(Font.HELVETICA, 16, Font.BOLD);
        Font mutedFont = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.GRAY);
        Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD);
        Font normalFont = new Font(Font.HELVETICA, 11, Font.NORMAL);
        Font labelFont = new Font(Font.HELVETICA, 11, Font.BOLD);

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 54, 54, 54, 54);
            PdfWriter.getInstance(document, out);
            document.open();

            document.add(new Paragraph(branding.displayName() != null ? branding.displayName() : "Clinic", clinicNameFont));
            addIfPresent(document, settings.address(), mutedFont);
            addIfPresent(document, joinNonNull(settings.supportPhone(), settings.supportEmail()), mutedFont);
            addIfPresent(document, settings.website(), mutedFont);

            document.add(new Paragraph(" "));
            document.add(new Paragraph("INVOICE", titleFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Invoice ID: " + invoice.getId(), normalFont));
            document.add(new Paragraph("Issued: " + ISSUED_FORMAT.format(invoice.getCreatedAt()), normalFont));
            addIfPresent(document, ownerReference, normalFont);
            addIfPresent(document, patientName != null ? "Patient: " + patientName : null, normalFont);
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(2);
            table.setWidthPercentage(60);
            table.setHorizontalAlignment(Element.ALIGN_LEFT);
            addRow(table, "Subtotal", invoice.getSubtotalAmount().toPlainString(), normalFont);
            if (invoice.getDiscountAmount() != null && invoice.getDiscountAmount().signum() > 0) {
                String label = invoice.getDiscountReason() != null ? "Discount (" + invoice.getDiscountReason() + ")" : "Discount";
                addRow(table, label, "-" + invoice.getDiscountAmount().toPlainString(), normalFont);
            }
            addRow(table, "Tax", invoice.getTaxAmount().toPlainString(), normalFont);
            addRow(table, "Total", invoice.getTotalAmount().toPlainString(), labelFont);
            document.add(table);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render invoice PDF for invoice " + invoice.getId(), e);
        }
    }

    private void addIfPresent(Document document, String text, Font font) throws DocumentException {
        if (text != null && !text.isBlank()) {
            document.add(new Paragraph(text, font));
        }
    }

    private String joinNonNull(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " · " + b;
    }

    private void addRow(PdfPTable table, String label, String value, Font valueFont) {
        PdfPCell labelCell = new PdfPCell(new Paragraph(label, valueFont));
        labelCell.setBorder(PdfPCell.NO_BORDER);
        PdfPCell valueCell = new PdfPCell(new Paragraph(value, valueFont));
        valueCell.setBorder(PdfPCell.NO_BORDER);
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(labelCell);
        table.addCell(valueCell);
    }
}
