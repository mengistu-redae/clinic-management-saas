package com.clinicops.laborder;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.labrate.LabTestRate;
import com.clinicops.labrate.LabTestRateRepository;
import com.clinicops.labrate.NoLabRateConfiguredException;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientProvisioningService;
import com.clinicops.patient.PatientRepository;
import com.clinicops.user.CurrentUserService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Order creation/editing plus the patient-initiated request -> confirm
 * -and-order flow live in one service, since both share the same pricing/
 * restricted-test/encounter-match logic - splitting them would just push
 * that sharing across two classes for no benefit. Status transitions
 * (LabOrderStatusService) and cancellation (LabOrderCancellationService)
 * are kept separate, mirroring CheckInService/CancellationService's own
 * separation from AppointmentService.
 */
@Service
public class LabOrderService {

    private final LabOrderRepository labOrderRepository;
    private final LabOrderTestRepository labOrderTestRepository;
    private final LabTestRateRepository labTestRateRepository;
    private final EncounterRepository encounterRepository;
    private final AppointmentRepository appointmentRepository;
    private final ClinicRepository clinicRepository;
    private final PatientRepository patientRepository;
    private final PatientProvisioningService patientProvisioningService;
    private final CurrentUserService currentUserService;
    private final LabOrderRefGenerator refGenerator;
    private final RestrictedTestsProperties restrictedTestsProperties;

    public LabOrderService(
            LabOrderRepository labOrderRepository,
            LabOrderTestRepository labOrderTestRepository,
            LabTestRateRepository labTestRateRepository,
            EncounterRepository encounterRepository,
            AppointmentRepository appointmentRepository,
            ClinicRepository clinicRepository,
            PatientRepository patientRepository,
            PatientProvisioningService patientProvisioningService,
            CurrentUserService currentUserService,
            LabOrderRefGenerator refGenerator,
            RestrictedTestsProperties restrictedTestsProperties) {
        this.labOrderRepository = labOrderRepository;
        this.labOrderTestRepository = labOrderTestRepository;
        this.labTestRateRepository = labTestRateRepository;
        this.encounterRepository = encounterRepository;
        this.appointmentRepository = appointmentRepository;
        this.clinicRepository = clinicRepository;
        this.patientRepository = patientRepository;
        this.patientProvisioningService = patientProvisioningService;
        this.currentUserService = currentUserService;
        this.refGenerator = refGenerator;
        this.restrictedTestsProperties = restrictedTestsProperties;
    }

    @Transactional
    public LabOrderWithTests create(UUID tenantId, CreateLabOrderRequest request) {
        if (request.encounterId() != null) {
            requireEncounterBelongsToPatient(request.encounterId(), tenantId, request.patientId());
        }

        LabOrder order = new LabOrder();
        order.setTenantId(tenantId);
        order.setPatientId(request.patientId());
        order.setEncounterId(request.encounterId());
        order.setOrderingProviderId(request.orderingProviderId());
        order.setNotes(request.notes());
        order.setPriority(request.priority() != null && !request.priority().isBlank() ? request.priority() : "routine");
        order.setStatus("ordered");
        order.setOrderRef(refGenerator.nextOrderRef());
        order.setClinicRef(refGenerator.nextClinicRef(tenantId, clinicName(tenantId)));
        LabOrder saved = labOrderRepository.save(order);

        List<LabOrderTest> tests = priceAndSaveTests(tenantId, saved.getId(), request.tests(), request.consentAcknowledged());
        saved.setTotalCost(sumPrices(tests));
        labOrderRepository.save(saved);

        return new LabOrderWithTests(saved, tests);
    }

    public LabOrderWithTests get(UUID id, UUID tenantId) {
        LabOrder order = labOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + id));
        return new LabOrderWithTests(order, labOrderTestRepository.findAllByLabOrderId(order.getId()));
    }

    public List<LabOrder> listForTenant(UUID tenantId) {
        return labOrderRepository.findAllByTenantId(tenantId);
    }

    /** Partial update - clinical fields only while status = "ordered"; `tests` null = don't touch, empty list is rejected. */
    @Transactional
    public LabOrderWithTests update(UUID id, UUID tenantId, UpdateLabOrderRequest request) {
        LabOrder order = labOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + id));

        boolean editingClinicalFields = request.patientId() != null || request.orderingProviderId() != null
                || request.notes() != null || request.tests() != null;
        if (editingClinicalFields && !"ordered".equals(order.getStatus())) {
            throw new InvalidLabOrderStatusException(
                    "Cannot edit a lab order with status '" + order.getStatus() + "' - only 'ordered' allows clinical edits");
        }

        if (request.patientId() != null) {
            order.setPatientId(request.patientId());
        }
        if (request.orderingProviderId() != null) {
            order.setOrderingProviderId(request.orderingProviderId());
        }
        if (request.notes() != null) {
            order.setNotes(request.notes());
        }
        if (request.priority() != null) {
            order.setPriority(request.priority());
        }

        List<LabOrderTest> tests;
        if (request.tests() != null) {
            if (request.tests().isEmpty()) {
                throw new InvalidLabOrderTestsException("tests cannot be an empty list - omit the field to leave the test list unchanged");
            }
            labOrderTestRepository.deleteAllByLabOrderId(order.getId());
            tests = priceAndSaveTests(tenantId, order.getId(), request.tests(), request.consentAcknowledged());
            order.setTotalCost(sumPrices(tests));
        } else {
            tests = labOrderTestRepository.findAllByLabOrderId(order.getId());
        }
        labOrderRepository.save(order);
        return new LabOrderWithTests(order, tests);
    }

    // ---- patient-initiated requests ----

    @Transactional
    public LabOrderWithTests createRequest(Jwt jwt, CreateLabRequestRequest request) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        Patient patient = patientProvisioningService.resolveForPortalUser(request.clinicId(), jwt);

        LabOrder order = new LabOrder();
        order.setTenantId(request.clinicId());
        order.setPatientId(patient.getId());
        order.setCustomerUserId(customerUserId);
        order.setNotes(request.notes());
        order.setStatus("requested");
        order.setOrderRef(refGenerator.nextOrderRef());
        order.setClinicRef(refGenerator.nextClinicRef(request.clinicId(), clinicName(request.clinicId())));
        LabOrder saved = labOrderRepository.save(order);

        List<LabOrderTest> tests = request.testNames().stream().map(name -> {
            LabOrderTest test = new LabOrderTest();
            test.setTenantId(request.clinicId());
            test.setLabOrderId(saved.getId());
            test.setTestName(name);
            return labOrderTestRepository.save(test);
        }).toList();

        return new LabOrderWithTests(saved, tests);
    }

    @Transactional
    public LabOrderWithTests confirmAndOrder(UUID id, UUID tenantId, ConfirmAndOrderRequest request) {
        LabOrder order = labOrderRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + id));
        if (!"requested".equals(order.getStatus())) {
            throw new RequestNotIssuableException(
                    "Lab order " + id + " is not a pending request (status: '" + order.getStatus() + "')");
        }
        if (request.encounterId() != null) {
            requireEncounterBelongsToPatient(request.encounterId(), tenantId, order.getPatientId());
        }

        order.setOrderingProviderId(request.orderingProviderId());
        order.setEncounterId(request.encounterId());
        order.setStatus("ordered");

        List<LabOrderTest> tests;
        if (request.tests() != null) {
            labOrderTestRepository.deleteAllByLabOrderId(order.getId());
            tests = priceAndSaveTests(tenantId, order.getId(), request.tests(), request.consentAcknowledged());
        } else {
            tests = repriceExisting(tenantId, order.getId(), request.consentAcknowledged());
        }
        order.setTotalCost(sumPrices(tests));
        labOrderRepository.save(order);
        return new LabOrderWithTests(order, tests);
    }

    /** Unions two ownership paths: orders requested directly, and orders on an encounter behind one of the patient's own appointments. */
    public List<LabOrderWithTests> myLabOrders(Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);

        Map<UUID, LabOrder> merged = new LinkedHashMap<>();
        for (LabOrder order : labOrderRepository.findAllByCustomerUserId(customerUserId)) {
            merged.put(order.getId(), order);
        }

        List<UUID> ownedEncounterIds = appointmentRepository.findAllByCustomerUserId(customerUserId).stream()
                .map(Appointment::getId)
                .map(encounterRepository::findByAppointmentId)
                .flatMap(Optional::stream)
                .map(Encounter::getId)
                .toList();
        if (!ownedEncounterIds.isEmpty()) {
            for (LabOrder order : labOrderRepository.findAllByEncounterIdIn(ownedEncounterIds)) {
                merged.put(order.getId(), order);
            }
        }

        return merged.values().stream()
                .map(order -> new LabOrderWithTests(order, labOrderTestRepository.findAllByLabOrderId(order.getId())))
                .toList();
    }

    // ---- public tracking ----

    public LabOrderTrackingView trackByRefAndPhone(String orderRef, String phone) {
        LabOrder order = labOrderRepository.findByOrderRef(orderRef)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + orderRef));
        boolean phoneMatches = patientRepository.findById(order.getPatientId())
                .map(Patient::getPhone)
                .map(phone::equals)
                .orElse(false);
        if (!phoneMatches) {
            throw new NoSuchElementException("Lab order not found: " + orderRef);
        }
        return new LabOrderTrackingView(
                order.getOrderRef(), order.getStatus(), order.getOrderedAt(), order.getSpecimenCollectedAt(),
                order.getSentAt(), order.getResultedAt(), order.getReviewedAt());
    }

    // ---- shared helpers ----

    private void requireEncounterBelongsToPatient(UUID encounterId, UUID tenantId, UUID patientId) {
        Encounter encounter = encounterRepository.findByIdAndTenantId(encounterId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Encounter not found: " + encounterId));
        Appointment appointment = appointmentRepository.findByIdAndTenantId(encounter.getAppointmentId(), tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + encounter.getAppointmentId()));
        if (!patientId.equals(appointment.getPatientId())) {
            throw new EncounterPatientMismatchException(
                    "Encounter " + encounterId + " does not belong to patient " + patientId);
        }
    }

    private List<LabOrderTest> priceAndSaveTests(UUID tenantId, UUID labOrderId, List<TestItem> items, boolean consentAcknowledged) {
        List<LabOrderTest> result = new ArrayList<>();
        for (TestItem item : items) {
            LabTestRate rate = requirePricedRate(tenantId, item.testCode(), consentAcknowledged);
            LabOrderTest test = new LabOrderTest();
            test.setTenantId(tenantId);
            test.setLabOrderId(labOrderId);
            test.setTestCode(item.testCode());
            test.setTestName(item.testName());
            test.setSpecimenType(item.specimenType());
            test.setNotes(item.notes());
            test.setPrice(rate.getBaseCharge().add(rate.getCollectionFee()));
            result.add(labOrderTestRepository.save(test));
        }
        return result;
    }

    /** confirm-and-order with no `tests` override - re-prices the existing rows in place using whatever testCode they already carry. */
    private List<LabOrderTest> repriceExisting(UUID tenantId, UUID labOrderId, boolean consentAcknowledged) {
        List<LabOrderTest> tests = labOrderTestRepository.findAllByLabOrderId(labOrderId);
        for (LabOrderTest test : tests) {
            if (test.getTestCode() == null || test.getTestCode().isBlank()) {
                throw new NoLabRateConfiguredException(
                        "Test '" + test.getTestName() + "' has no testCode assigned yet - provide `tests` in this request to assign one");
            }
            LabTestRate rate = requirePricedRate(tenantId, test.getTestCode(), consentAcknowledged);
            test.setPrice(rate.getBaseCharge().add(rate.getCollectionFee()));
            labOrderTestRepository.save(test);
        }
        return tests;
    }

    private LabTestRate requirePricedRate(UUID tenantId, String testCode, boolean consentAcknowledged) {
        if (restrictedTestsProperties.isRestricted(testCode) && !consentAcknowledged) {
            throw new RestrictedTestException("Test " + testCode + " requires explicit consent acknowledgement");
        }
        return labTestRateRepository.findByTenantIdAndTestCode(tenantId, testCode)
                .orElseThrow(() -> new NoLabRateConfiguredException("No lab rate configured for test code: " + testCode));
    }

    private BigDecimal sumPrices(List<LabOrderTest> tests) {
        return tests.stream().map(LabOrderTest::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String clinicName(UUID tenantId) {
        return clinicRepository.findById(tenantId).map(Clinic::getName).orElse(null);
    }
}
