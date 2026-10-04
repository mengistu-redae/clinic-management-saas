package com.clinicops.visitsummary;

import com.clinicops.allergy.Allergy;
import com.clinicops.allergy.AllergyRepository;
import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.clinicsettings.ClinicBrandingView;
import com.clinicops.clinicsettings.ClinicSettingsService;
import com.clinicops.clinicsettings.EffectiveClinicSettings;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.immunization.Immunization;
import com.clinicops.immunization.ImmunizationRepository;
import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import com.clinicops.vitals.Vitals;
import com.clinicops.vitals.VitalsRepository;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Renders a visit summary on demand from live data, never persisted -
 * same reasoning InvoicePdfService already established (nothing to gain
 * from persisting bytes that are regenerable at zero cost, and a signed
 * encounter can still gain addenda afterward, so a stale cached copy
 * would actively mislead). One render method serves both the staff and
 * patient controllers - unlike Invoice, which needed two render* methods
 * only because it has two structurally different owner types (appointment
 * vs lab order), a visit summary has exactly one owner type, always an
 * appointment, so both callers just resolve ownership differently and
 * delegate here with the confirmed (appointmentId, tenantId) pair.
 *
 * Every section is printed only if there's data for it - a freshly
 * `booked` appointment with nothing documented yet still renders a valid,
 * mostly-empty PDF rather than failing.
 */
@Service
public class VisitSummaryPdfService {

    private static final DateTimeFormatter VISIT_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a").withZone(ZoneOffset.UTC);
    private static final Color HEADER_FILL = new Color(230, 230, 230);

    /** Body-system labels in the same fixed order Encounter's own columns use (phase 25). */
    private static final String[][] EXAM_SYSTEMS = {
            {"generalAppearance", "General Appearance"},
            {"heent", "HEENT"},
            {"cardiovascular", "Cardiovascular"},
            {"respiratory", "Respiratory"},
            {"abdominal", "Abdominal"},
            {"musculoskeletal", "Musculoskeletal"},
            {"neurological", "Neurological"},
            {"skin", "Skin"},
            {"psychiatric", "Psychiatric"},
    };

    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final ProviderRepository providerRepository;
    private final PatientRepository patientRepository;
    private final EncounterRepository encounterRepository;
    private final PrescriptionRepository prescriptionRepository;
    private final VitalsRepository vitalsRepository;
    private final AllergyRepository allergyRepository;
    private final ImmunizationRepository immunizationRepository;
    private final ClinicSettingsService clinicSettingsService;

    public VisitSummaryPdfService(
            AppointmentRepository appointmentRepository,
            SlotRepository slotRepository,
            ProviderRepository providerRepository,
            PatientRepository patientRepository,
            EncounterRepository encounterRepository,
            PrescriptionRepository prescriptionRepository,
            VitalsRepository vitalsRepository,
            AllergyRepository allergyRepository,
            ImmunizationRepository immunizationRepository,
            ClinicSettingsService clinicSettingsService) {
        this.appointmentRepository = appointmentRepository;
        this.slotRepository = slotRepository;
        this.providerRepository = providerRepository;
        this.patientRepository = patientRepository;
        this.encounterRepository = encounterRepository;
        this.prescriptionRepository = prescriptionRepository;
        this.vitalsRepository = vitalsRepository;
        this.allergyRepository = allergyRepository;
        this.immunizationRepository = immunizationRepository;
        this.clinicSettingsService = clinicSettingsService;
    }

    public byte[] render(UUID appointmentId, UUID tenantId) {
        Appointment appointment = appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));

        ClinicBrandingView branding = clinicSettingsService.getBranding(tenantId);
        EffectiveClinicSettings settings = clinicSettingsService.resolve(tenantId);

        Font clinicNameFont = new Font(Font.HELVETICA, 16, Font.BOLD);
        Font mutedFont = new Font(Font.HELVETICA, 9, Font.NORMAL, Color.GRAY);
        Font titleFont = new Font(Font.HELVETICA, 20, Font.BOLD);
        Font sectionFont = new Font(Font.HELVETICA, 13, Font.BOLD);
        Font normalFont = new Font(Font.HELVETICA, 11, Font.NORMAL);
        Font labelFont = new Font(Font.HELVETICA, 11, Font.BOLD);
        Font headerCellFont = new Font(Font.HELVETICA, 9, Font.BOLD);
        Font cellFont = new Font(Font.HELVETICA, 9, Font.NORMAL);

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
            document.add(new Paragraph("VISIT SUMMARY", titleFont));
            document.add(new Paragraph(" "));

            document.add(new Paragraph("Reference: " + appointment.getAppointmentRef(), normalFont));
            addIfPresent(document, visitDateTime(appointment), normalFont);
            addIfPresent(document, providerLine(appointment, tenantId), normalFont);
            addIfPresent(document, patientLine(appointment, tenantId), normalFont);
            document.add(new Paragraph(" "));

            Optional<Vitals> vitals = vitalsRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId);
            vitals.ifPresent(v -> addVitals(document, v, sectionFont, headerCellFont, cellFont));

            if (appointment.getPatientId() != null) {
                List<Allergy> activeAllergies = allergyRepository.findAllByPatientIdAndTenantId(appointment.getPatientId(), tenantId)
                        .stream().filter(a -> "active".equals(a.getStatus())).toList();
                if (!activeAllergies.isEmpty()) {
                    addAllergies(document, activeAllergies, sectionFont, headerCellFont, cellFont);
                }
            }

            Optional<Encounter> encounter = encounterRepository.findByAppointmentIdAndTenantId(appointmentId, tenantId);
            if (encounter.isPresent()) {
                addClinicalNote(document, encounter.get(), sectionFont, labelFont, normalFont);
                addExamFindings(document, encounter.get(), sectionFont, headerCellFont, cellFont);

                List<Prescription> prescriptions = prescriptionRepository.findAllByEncounterId(encounter.get().getId());
                if (!prescriptions.isEmpty()) {
                    addPrescriptions(document, prescriptions, sectionFont, headerCellFont, cellFont);
                }
            }

            List<Immunization> immunizations = immunizationRepository.findAllByAppointmentIdAndTenantId(appointmentId, tenantId);
            if (!immunizations.isEmpty()) {
                addImmunizations(document, immunizations, sectionFont, headerCellFont, cellFont);
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render visit summary PDF for appointment " + appointmentId, e);
        }
    }

    private String visitDateTime(Appointment appointment) {
        Slot slot = slotRepository.findById(appointment.getSlotId()).orElse(null);
        if (slot == null) {
            return null;
        }
        return "Visit date: " + VISIT_DATE_FORMAT.format(slot.getStartTime());
    }

    private String providerLine(Appointment appointment, UUID tenantId) {
        return providerRepository.findByIdAndTenantId(appointment.getProviderId(), tenantId)
                .map(p -> "Provider: " + p.getFullName())
                .orElse(null);
    }

    private String patientLine(Appointment appointment, UUID tenantId) {
        String name = resolvePatientName(appointment, tenantId);
        return name != null ? "Patient: " + name : null;
    }

    private String resolvePatientName(Appointment appointment, UUID tenantId) {
        if (appointment.getPatientId() != null) {
            return patientRepository.findByIdAndTenantId(appointment.getPatientId(), tenantId)
                    .map(p -> p.getFirstName() + " " + p.getLastName())
                    .orElse(appointment.getContactName());
        }
        return appointment.getContactName();
    }

    private void addVitals(Document document, Vitals v, Font sectionFont, Font headerFont, Font cellFont) {
        try {
            document.add(new Paragraph("Vitals", sectionFont));
            String[] headers = {"Height (cm)", "Weight (kg)", "BMI", "Temp (C)", "Pulse", "Resp. Rate", "BP", "O2 Sat (%)", "Pain"};
            String bp = v.getBloodPressureSystolic() != null && v.getBloodPressureDiastolic() != null
                    ? v.getBloodPressureSystolic() + "/" + v.getBloodPressureDiastolic() : null;
            String[] row = {
                    string(v.getHeightCm()), string(v.getWeightKg()), string(v.getBmi()),
                    string(v.getTemperatureC()), string(v.getPulseBpm()), string(v.getRespiratoryRate()),
                    bp, string(v.getOxygenSaturationPct()), string(v.getPainScore())
            };
            addTable(document, headers, java.util.Collections.singletonList(row), headerFont, cellFont);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render vitals section", e);
        }
    }

    private void addAllergies(Document document, List<Allergy> allergies, Font sectionFont, Font headerFont, Font cellFont) {
        try {
            document.add(new Paragraph("Allergies", sectionFont));
            String[] headers = {"Allergen", "Reaction", "Severity"};
            List<String[]> rows = allergies.stream()
                    .map(a -> new String[]{a.getAllergen(), a.getReactionType(), a.getSeverity()})
                    .toList();
            addTable(document, headers, rows, headerFont, cellFont);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render allergies section", e);
        }
    }

    private void addClinicalNote(Document document, Encounter encounter, Font sectionFont, Font labelFont, Font normalFont) {
        try {
            document.add(new Paragraph("Clinical Note", sectionFont));
            addLabeledParagraph(document, "Chief Complaint", encounter.getChiefComplaint(), labelFont, normalFont);
            addLabeledParagraph(document, "Assessment", encounter.getAssessment(), labelFont, normalFont);
            addLabeledParagraph(document, "Plan", encounter.getPlan(), labelFont, normalFont);
            addLabeledParagraph(document, "ICD-10 Codes", encounter.getIcd10Codes(), labelFont, normalFont);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render clinical note section", e);
        }
    }

    private void addExamFindings(Document document, Encounter encounter, Font sectionFont, Font headerFont, Font cellFont) {
        List<String[]> rows = new java.util.ArrayList<>();
        for (String[] system : EXAM_SYSTEMS) {
            Boolean normal = examNormal(encounter, system[0]);
            if (normal == null) {
                continue;
            }
            String note = examNote(encounter, system[0]);
            rows.add(new String[]{system[1], normal ? "Normal" : "Abnormal", note});
        }
        if (rows.isEmpty()) {
            return;
        }
        try {
            document.add(new Paragraph("Physical Exam Findings", sectionFont));
            addTable(document, new String[]{"System", "Finding", "Note"}, rows, headerFont, cellFont);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render exam findings section", e);
        }
    }

    private Boolean examNormal(Encounter e, String system) {
        return switch (system) {
            case "generalAppearance" -> e.getGeneralAppearanceNormal();
            case "heent" -> e.getHeentNormal();
            case "cardiovascular" -> e.getCardiovascularNormal();
            case "respiratory" -> e.getRespiratoryNormal();
            case "abdominal" -> e.getAbdominalNormal();
            case "musculoskeletal" -> e.getMusculoskeletalNormal();
            case "neurological" -> e.getNeurologicalNormal();
            case "skin" -> e.getSkinNormal();
            case "psychiatric" -> e.getPsychiatricNormal();
            default -> null;
        };
    }

    private String examNote(Encounter e, String system) {
        return switch (system) {
            case "generalAppearance" -> e.getGeneralAppearanceNote();
            case "heent" -> e.getHeentNote();
            case "cardiovascular" -> e.getCardiovascularNote();
            case "respiratory" -> e.getRespiratoryNote();
            case "abdominal" -> e.getAbdominalNote();
            case "musculoskeletal" -> e.getMusculoskeletalNote();
            case "neurological" -> e.getNeurologicalNote();
            case "skin" -> e.getSkinNote();
            case "psychiatric" -> e.getPsychiatricNote();
            default -> null;
        };
    }

    private void addPrescriptions(Document document, List<Prescription> prescriptions, Font sectionFont, Font headerFont, Font cellFont) {
        try {
            document.add(new Paragraph("Prescriptions", sectionFont));
            String[] headers = {"Medication", "Dosage", "Route", "Frequency", "Duration", "Instructions"};
            List<String[]> rows = prescriptions.stream()
                    .map(p -> new String[]{p.getMedicationName(), p.getDosage(), p.getRoute(), p.getFrequency(), p.getDuration(), p.getInstructions()})
                    .toList();
            addTable(document, headers, rows, headerFont, cellFont);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render prescriptions section", e);
        }
    }

    private void addImmunizations(Document document, List<Immunization> immunizations, Font sectionFont, Font headerFont, Font cellFont) {
        try {
            document.add(new Paragraph("Immunizations Given This Visit", sectionFont));
            String[] headers = {"Vaccine", "Dose #", "Lot #", "Site"};
            List<String[]> rows = immunizations.stream()
                    .map(i -> new String[]{i.getVaccineName(), string(i.getDoseNumber()), i.getLotNumber(), i.getSite()})
                    .toList();
            addTable(document, headers, rows, headerFont, cellFont);
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render immunizations section", e);
        }
    }

    private void addLabeledParagraph(Document document, String label, String value, Font labelFont, Font normalFont) throws DocumentException {
        if (value == null || value.isBlank()) {
            return;
        }
        document.add(new Paragraph(label + ":", labelFont));
        document.add(new Paragraph(value, normalFont));
    }

    private void addTable(Document document, String[] headers, List<String[]> rows, Font headerFont, Font cellFont) throws DocumentException {
        PdfPTable table = new PdfPTable(headers.length);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4);
        table.setSpacingAfter(10);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Paragraph(header, headerFont));
            cell.setBackgroundColor(HEADER_FILL);
            table.addCell(cell);
        }
        for (String[] row : rows) {
            for (String value : row) {
                PdfPCell cell = new PdfPCell(new Paragraph(value != null && !value.isBlank() ? value : "-", cellFont));
                table.addCell(cell);
            }
        }
        document.add(table);
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

    private String string(Object value) {
        return value != null ? value.toString() : null;
    }
}
