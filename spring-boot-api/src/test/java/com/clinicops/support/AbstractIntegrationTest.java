package com.clinicops.support;

import com.clinicops.accounting.Account;
import com.clinicops.accounting.AccountRepository;
import com.clinicops.accounting.JournalEntry;
import com.clinicops.accounting.JournalEntryRepository;
import com.clinicops.accounting.JournalLine;
import com.clinicops.accounting.JournalLineRepository;
import com.clinicops.finance.Budget;
import com.clinicops.finance.BudgetRepository;
import com.clinicops.finance.Employee;
import com.clinicops.finance.EmployeeRepository;
import com.clinicops.finance.PayrollPayment;
import com.clinicops.finance.PayrollPaymentRepository;
import com.clinicops.finance.PayrollRun;
import com.clinicops.finance.PayrollRunRepository;
import com.clinicops.immunization.Immunization;
import com.clinicops.immunization.ImmunizationRepository;
import com.clinicops.allergy.Allergy;
import com.clinicops.allergy.AllergyRepository;
import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.clinicsettings.ClinicSettingsRepository;
import com.clinicops.consent.ConsentRecord;
import com.clinicops.consent.ConsentRecordRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.feepolicy.FeePolicy;
import com.clinicops.feepolicy.FeePolicyRepository;
import com.clinicops.labrate.LabTestRate;
import com.clinicops.labrate.LabTestRateRepository;
import com.clinicops.medicalhistory.MedicalHistory;
import com.clinicops.medicalhistory.MedicalHistoryRepository;
import com.clinicops.encounter.Prescription;
import com.clinicops.encounter.PrescriptionRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.pharmacy.DispenseRecord;
import com.clinicops.pharmacy.DispenseRecordRepository;
import com.clinicops.pharmacy.DrugInteractionPair;
import com.clinicops.pharmacy.DrugInteractionPairRepository;
import com.clinicops.pharmacy.PendingControlledSubstanceDispenseRepository;
import com.clinicops.pharmacy.Medication;
import com.clinicops.pharmacy.MedicationRepository;
import com.clinicops.inventory.Asset;
import com.clinicops.inventory.AssetRepository;
import com.clinicops.inventory.AssetMaintenanceRecord;
import com.clinicops.inventory.AssetMaintenanceRecordRepository;
import com.clinicops.inventory.InventoryItem;
import com.clinicops.inventory.InventoryItemRepository;
import com.clinicops.inventory.Supplier;
import com.clinicops.inventory.SupplierRepository;
import com.clinicops.inventory.PurchaseOrder;
import com.clinicops.inventory.PurchaseOrderRepository;
import com.clinicops.inventory.PurchaseOrderLine;
import com.clinicops.inventory.PurchaseOrderLineRepository;
import com.clinicops.inventory.StockAdjustment;
import com.clinicops.inventory.StockAdjustmentRepository;
import com.clinicops.inventory.StockBatch;
import com.clinicops.inventory.StockBatchRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.provider.ProviderWorkingHours;
import com.clinicops.provider.ProviderWorkingHoursRepository;
import com.clinicops.referral.Referral;
import com.clinicops.referral.ReferralRepository;
import com.clinicops.room.Room;
import com.clinicops.room.RoomRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
import com.clinicops.vitals.Vitals;
import com.clinicops.vitals.VitalsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Base class for controller-level integration tests: a real Spring context,
 * MockMvc driven through the actual filter chain (Spring Security,
 * TenantContextFilter, @PreAuthorize), and real Postgres + Redis via
 * Testcontainers - the same "ddl-auto: validate against real Flyway
 * migrations" behavior as production, not a stand-in like H2. Images match
 * what docker-compose already uses (postgres:16-alpine, redis:7-alpine) so
 * nothing new needs pulling on a machine that's already run
 * `docker compose up`.
 *
 * Auth is the one thing faked: production authenticates by validating a real
 * Keycloak-issued JWT against KEYCLOAK_ISSUER_URI. These tests instead build
 * a Jwt object directly and inject it via Spring Security Test's jwt()
 * request post-processor - TenantContextFilter, the real
 * JwtAuthenticationConverter bean (autowired below, not re-implemented) and
 * every @PreAuthorize check still run for real; only token issuance is
 * stubbed. See NoNetworkJwtDecoderConfig for why the autoconfigured
 * JwtDecoder (which would otherwise try to reach a real issuer at context
 * startup) is replaced.
 *
 * The containers use the Testcontainers <b>singleton pattern</b> - started
 * once in a static initializer and never explicitly stopped (Ryuk reaps them
 * at JVM exit). This is deliberate: {@code @Testcontainers} + {@code @Container}
 * stop a static container after each test <i>class</i>, but the Spring
 * context is cached and shared across classes - so the first class to finish
 * would kill the container out from under every later test's cached
 * datasource. One container for the whole JVM fork keeps every cached
 * context valid. Wired in via {@code @DynamicPropertySource} rather than
 * {@code @ServiceConnection} so Spring Boot's own container lifecycle
 * management never enters the picture either.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AbstractIntegrationTest.NoNetworkJwtDecoderConfig.class)
public abstract class AbstractIntegrationTest {

    @SuppressWarnings("resource") // singleton - lives for the JVM, reaped by Ryuk at exit
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Autowired
    protected ClinicRepository clinicRepository;

    @Autowired
    protected ProviderRepository providerRepository;

    @Autowired
    protected ProviderWorkingHoursRepository providerWorkingHoursRepository;

    @Autowired
    protected AppointmentTypeRepository appointmentTypeRepository;

    @Autowired
    protected PatientRepository patientRepository;

    @Autowired
    protected SlotRepository slotRepository;

    @Autowired
    protected FeePolicyRepository feePolicyRepository;

    @Autowired
    protected AppointmentRepository appointmentRepository;

    @Autowired
    protected AppUserRepository appUserRepository;

    @Autowired
    protected com.clinicops.notification.NotificationRepository notificationRepository;

    @Autowired
    protected RoomRepository roomRepository;

    @Autowired
    protected ClinicSettingsRepository clinicSettingsRepository;

    @Autowired
    protected EncounterRepository encounterRepository;

    @Autowired
    protected LabTestRateRepository labTestRateRepository;

    @Autowired
    protected AllergyRepository allergyRepository;

    @Autowired
    protected VitalsRepository vitalsRepository;

    @Autowired
    protected ImmunizationRepository immunizationRepository;

    @Autowired
    protected MedicalHistoryRepository medicalHistoryRepository;

    @Autowired
    protected ConsentRecordRepository consentRecordRepository;

    @Autowired
    protected ReferralRepository referralRepository;

    @Autowired
    protected PrescriptionRepository prescriptionRepository;

    @Autowired
    protected MedicationRepository medicationRepository;

    @Autowired
    protected StockBatchRepository stockBatchRepository;

    @Autowired
    protected DrugInteractionPairRepository drugInteractionPairRepository;

    @Autowired
    protected PendingControlledSubstanceDispenseRepository pendingControlledSubstanceDispenseRepository;

    @Autowired
    protected InventoryItemRepository inventoryItemRepository;

    @Autowired
    protected SupplierRepository supplierRepository;

    @Autowired
    protected PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    protected PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Autowired
    protected StockAdjustmentRepository stockAdjustmentRepository;

    @Autowired
    protected AssetRepository assetRepository;

    @Autowired
    protected AssetMaintenanceRecordRepository assetMaintenanceRecordRepository;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    protected JournalEntryRepository journalEntryRepository;

    @Autowired
    protected JournalLineRepository journalLineRepository;

    @Autowired
    protected EmployeeRepository employeeRepository;

    @Autowired
    protected PayrollRunRepository payrollRunRepository;

    @Autowired
    protected PayrollPaymentRepository payrollPaymentRepository;

    @Autowired
    protected BudgetRepository budgetRepository;

    @Autowired
    protected DispenseRecordRepository dispenseRecordRepository;

    // ---- fixture builders: seed just enough of the tenant-scoped schema
    // for a test's own scenario, letting Flyway/Postgres enforce the same
    // FKs and NOT NULLs production does. ----

    protected Clinic createClinic(String keycloakOrgAlias, String name) {
        Clinic clinic = new Clinic();
        clinic.setKeycloakOrgId(keycloakOrgAlias);
        clinic.setName(name);
        return clinicRepository.save(clinic);
    }

    protected Provider createProvider(UUID tenantId, String fullName) {
        Provider provider = new Provider();
        provider.setTenantId(tenantId);
        provider.setFullName(fullName);
        return providerRepository.save(provider);
    }

    /**
     * A Provider row linked to a login - keycloakSubject must match the
     * "subject" passed to asProvider(...) for the same test, so
     * CurrentProviderService resolves back to this exact provider. Provisions
     * the AppUser row directly rather than going through a real login, same
     * end state CurrentUserService.resolveAppUser would produce on first call.
     */
    protected Provider createProviderLinkedToAppUser(UUID tenantId, String fullName, String keycloakSubject) {
        AppUser appUser = appUserRepository.findByKeycloakUserId(keycloakSubject).orElseGet(() -> {
            AppUser fresh = new AppUser();
            fresh.setKeycloakUserId(keycloakSubject);
            fresh.setEmail(keycloakSubject + "@example.test");
            fresh.setDisplayName(keycloakSubject);
            return appUserRepository.save(fresh);
        });
        Provider provider = new Provider();
        provider.setTenantId(tenantId);
        provider.setFullName(fullName);
        provider.setAppUserId(appUser.getId());
        return providerRepository.save(provider);
    }

    /** dayOfWeek: 0=Sunday..6=Saturday - see ProviderWorkingHours/SlotGenerator. */
    protected ProviderWorkingHours createWorkingHours(UUID tenantId, UUID providerId, int dayOfWeek, LocalTime start, LocalTime end) {
        ProviderWorkingHours hours = new ProviderWorkingHours();
        hours.setTenantId(tenantId);
        hours.setProviderId(providerId);
        hours.setDayOfWeek((short) dayOfWeek);
        hours.setStartTime(start);
        hours.setEndTime(end);
        return providerWorkingHoursRepository.save(hours);
    }

    protected Room createRoom(UUID tenantId, String name) {
        Room room = new Room();
        room.setTenantId(tenantId);
        room.setName(name);
        return roomRepository.save(room);
    }

    protected AppointmentType createAppointmentType(UUID tenantId, String name, int durationMinutes, String price) {
        AppointmentType type = new AppointmentType();
        type.setTenantId(tenantId);
        type.setName(name);
        type.setDurationMinutes(durationMinutes);
        type.setPriceAmount(new BigDecimal(price));
        return appointmentTypeRepository.save(type);
    }

    protected Patient createPatient(UUID tenantId, String firstName, String lastName, String phone) {
        Patient patient = new Patient();
        patient.setTenantId(tenantId);
        patient.setFirstName(firstName);
        patient.setLastName(lastName);
        patient.setPhone(phone);
        return patientRepository.save(patient);
    }

    protected Slot createSlot(UUID tenantId, UUID providerId, UUID appointmentTypeId, Instant start, Instant end) {
        Slot slot = new Slot();
        slot.setTenantId(tenantId);
        slot.setProviderId(providerId);
        slot.setAppointmentTypeId(appointmentTypeId);
        slot.setStartTime(start);
        slot.setEndTime(end);
        return slotRepository.save(slot);
    }

    protected Encounter createEncounter(UUID tenantId, UUID appointmentId, UUID providerId) {
        Encounter encounter = new Encounter();
        encounter.setTenantId(tenantId);
        encounter.setAppointmentId(appointmentId);
        encounter.setProviderId(providerId);
        return encounterRepository.save(encounter);
    }

    protected Prescription createPrescription(UUID tenantId, UUID encounterId, String medicationName, Integer quantityPrescribed) {
        Prescription prescription = new Prescription();
        prescription.setTenantId(tenantId);
        prescription.setEncounterId(encounterId);
        prescription.setMedicationName(medicationName);
        prescription.setQuantityDispensed(quantityPrescribed);
        return prescriptionRepository.save(prescription);
    }

    protected Medication createMedication(UUID tenantId, String name) {
        Medication medication = new Medication();
        medication.setTenantId(tenantId);
        medication.setName(name);
        return medicationRepository.save(medication);
    }

    protected Account createAccount(UUID tenantId, String code, String name, String type) {
        Account account = new Account();
        account.setTenantId(tenantId);
        account.setCode(code);
        account.setName(name);
        account.setType(type);
        return accountRepository.save(account);
    }

    protected JournalEntry createJournalEntry(UUID tenantId, String description, String sourceType, UUID sourceId) {
        JournalEntry entry = new JournalEntry();
        entry.setTenantId(tenantId);
        entry.setDescription(description);
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        return journalEntryRepository.save(entry);
    }

    /** Same find-or-create-by-keycloak-subject idiom as createProviderLinkedToAppUser - email always "<subject>@example.test", matching jwtRequest's own claim so a test can create an Employee by that same email. */
    protected AppUser createAppUser(String keycloakSubject) {
        return appUserRepository.findByKeycloakUserId(keycloakSubject).orElseGet(() -> {
            AppUser fresh = new AppUser();
            fresh.setKeycloakUserId(keycloakSubject);
            fresh.setEmail(keycloakSubject + "@example.test");
            fresh.setDisplayName(keycloakSubject);
            return appUserRepository.save(fresh);
        });
    }

    protected Employee createEmployee(UUID tenantId, UUID appUserId, String email, java.math.BigDecimal salaryAmount) {
        Employee employee = new Employee();
        employee.setTenantId(tenantId);
        employee.setAppUserId(appUserId);
        employee.setEmail(email);
        employee.setSalaryAmount(salaryAmount);
        return employeeRepository.save(employee);
    }

    protected PayrollRun createPayrollRun(UUID tenantId, int year, int month, java.math.BigDecimal totalAmount) {
        PayrollRun run = new PayrollRun();
        run.setTenantId(tenantId);
        run.setYear(year);
        run.setMonth(month);
        run.setTotalAmount(totalAmount);
        return payrollRunRepository.save(run);
    }

    protected PayrollPayment createPayrollPayment(UUID tenantId, UUID payrollRunId, UUID employeeId, java.math.BigDecimal amount, UUID journalEntryId) {
        PayrollPayment payment = new PayrollPayment();
        payment.setTenantId(tenantId);
        payment.setPayrollRunId(payrollRunId);
        payment.setEmployeeId(employeeId);
        payment.setAmount(amount);
        payment.setJournalEntryId(journalEntryId);
        return payrollPaymentRepository.save(payment);
    }

    protected Budget createBudget(UUID tenantId, UUID accountId, int year, int month, java.math.BigDecimal amount) {
        Budget budget = new Budget();
        budget.setTenantId(tenantId);
        budget.setAccountId(accountId);
        budget.setYear(year);
        budget.setMonth(month);
        budget.setAmount(amount);
        return budgetRepository.save(budget);
    }

    protected JournalLine createJournalLine(UUID tenantId, UUID journalEntryId, UUID accountId, String entryType, java.math.BigDecimal amount) {
        JournalLine line = new JournalLine();
        line.setTenantId(tenantId);
        line.setJournalEntryId(journalEntryId);
        line.setAccountId(accountId);
        line.setEntryType(entryType);
        line.setAmount(amount);
        return journalLineRepository.save(line);
    }

    protected StockBatch createStockBatch(UUID tenantId, UUID medicationId, int quantity) {
        StockBatch batch = new StockBatch();
        batch.setTenantId(tenantId);
        batch.setMedicationId(medicationId);
        batch.setQuantityReceived(quantity);
        batch.setQuantityOnHand(quantity);
        return stockBatchRepository.save(batch);
    }

    /** Phase 29 - same shape as createStockBatch(tenantId, medicationId, quantity), owned by an InventoryItem instead. */
    protected StockBatch createStockBatchForItem(UUID tenantId, UUID inventoryItemId, int quantity) {
        StockBatch batch = new StockBatch();
        batch.setTenantId(tenantId);
        batch.setInventoryItemId(inventoryItemId);
        batch.setQuantityReceived(quantity);
        batch.setQuantityOnHand(quantity);
        return stockBatchRepository.save(batch);
    }

    /** Phase 34 - a direct insert (not through DispenseService) so a test can control createdAt for day-bucketed analytics assertions. createdAt must be set before the first save - the column is updatable=false, so a later save wouldn't move it. */
    protected DispenseRecord createDispenseRecord(UUID tenantId, UUID medicationId, int quantityDispensed, java.time.Instant createdAt) {
        DispenseRecord record = new DispenseRecord();
        record.setTenantId(tenantId);
        record.setMedicationId(medicationId);
        record.setPrescriptionId(UUID.randomUUID());
        record.setStockBatchId(UUID.randomUUID());
        record.setQuantityDispensed(quantityDispensed);
        record.setCreatedAt(createdAt);
        return dispenseRecordRepository.save(record);
    }

    protected InventoryItem createInventoryItem(UUID tenantId, String name) {
        InventoryItem item = new InventoryItem();
        item.setTenantId(tenantId);
        item.setName(name);
        item.setCategory("clinical_supply");
        return inventoryItemRepository.save(item);
    }

    protected Supplier createSupplier(UUID tenantId, String name) {
        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        supplier.setName(name);
        return supplierRepository.save(supplier);
    }

    protected PurchaseOrder createPurchaseOrder(UUID tenantId, UUID supplierId) {
        PurchaseOrder order = new PurchaseOrder();
        order.setTenantId(tenantId);
        order.setSupplierId(supplierId);
        return purchaseOrderRepository.save(order);
    }

    protected PurchaseOrderLine createPurchaseOrderLine(UUID tenantId, UUID purchaseOrderId, UUID medicationId, UUID inventoryItemId, int quantityOrdered) {
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.setTenantId(tenantId);
        line.setPurchaseOrderId(purchaseOrderId);
        line.setMedicationId(medicationId);
        line.setInventoryItemId(inventoryItemId);
        line.setQuantityOrdered(quantityOrdered);
        return purchaseOrderLineRepository.save(line);
    }

    protected StockAdjustment createStockAdjustment(UUID tenantId, UUID stockBatchId, int quantityDelta) {
        StockAdjustment adjustment = new StockAdjustment();
        adjustment.setTenantId(tenantId);
        adjustment.setStockBatchId(stockBatchId);
        adjustment.setQuantityDelta(quantityDelta);
        adjustment.setReason("correction");
        return stockAdjustmentRepository.save(adjustment);
    }

    protected Asset createAsset(UUID tenantId, String name) {
        Asset asset = new Asset();
        asset.setTenantId(tenantId);
        asset.setName(name);
        return assetRepository.save(asset);
    }

    protected AssetMaintenanceRecord createAssetMaintenanceRecord(UUID tenantId, UUID assetId, String description) {
        AssetMaintenanceRecord record = new AssetMaintenanceRecord();
        record.setTenantId(tenantId);
        record.setAssetId(assetId);
        record.setDescription(description);
        return assetMaintenanceRecordRepository.save(record);
    }

    protected DrugInteractionPair createDrugInteractionPair(UUID tenantId, UUID medicationAId, UUID medicationBId) {
        DrugInteractionPair pair = new DrugInteractionPair();
        pair.setTenantId(tenantId);
        pair.setMedicationAId(medicationAId);
        pair.setMedicationBId(medicationBId);
        return drugInteractionPairRepository.save(pair);
    }

    protected Allergy createAllergy(UUID tenantId, UUID patientId, String allergen) {
        Allergy allergy = new Allergy();
        allergy.setTenantId(tenantId);
        allergy.setPatientId(patientId);
        allergy.setAllergen(allergen);
        return allergyRepository.save(allergy);
    }

    protected Immunization createImmunization(UUID tenantId, UUID patientId, String vaccineName) {
        Immunization immunization = new Immunization();
        immunization.setTenantId(tenantId);
        immunization.setPatientId(patientId);
        immunization.setVaccineName(vaccineName);
        immunization.setAdministeredAt(java.time.LocalDate.now());
        return immunizationRepository.save(immunization);
    }

    protected Vitals createVitals(UUID tenantId, UUID appointmentId) {
        Vitals vitals = new Vitals();
        vitals.setTenantId(tenantId);
        vitals.setAppointmentId(appointmentId);
        return vitalsRepository.save(vitals);
    }

    protected MedicalHistory createMedicalHistory(UUID tenantId, UUID patientId) {
        MedicalHistory history = new MedicalHistory();
        history.setTenantId(tenantId);
        history.setPatientId(patientId);
        return medicalHistoryRepository.save(history);
    }

    protected ConsentRecord createConsentRecord(UUID tenantId, UUID patientId, String consentType) {
        ConsentRecord record = new ConsentRecord();
        record.setTenantId(tenantId);
        record.setPatientId(patientId);
        record.setConsentType(consentType);
        record.setPolicyVersion("v1");
        return consentRecordRepository.save(record);
    }

    /** Internal referral (receivingProviderId set) - callers seeding an external one set the external fields directly on the returned entity before further use. */
    protected Referral createReferral(UUID tenantId, UUID patientId, UUID referringProviderId, UUID receivingProviderId) {
        Referral referral = new Referral();
        referral.setTenantId(tenantId);
        referral.setPatientId(patientId);
        referral.setReferringProviderId(referringProviderId);
        referral.setReceivingProviderId(receivingProviderId);
        referral.setReason("Specialist consult");
        return referralRepository.save(referral);
    }

    protected LabTestRate createLabTestRate(UUID tenantId, String testCode, String baseCharge, String collectionFee) {
        LabTestRate rate = new LabTestRate();
        rate.setTenantId(tenantId);
        rate.setTestCode(testCode);
        rate.setBaseCharge(new BigDecimal(baseCharge));
        rate.setCollectionFee(new BigDecimal(collectionFee));
        return labTestRateRepository.save(rate);
    }

    /** providerId null = a clinic-wide default tier. */
    protected FeePolicy createFeePolicy(UUID tenantId, UUID providerId, int cutoffHours, int feePercent) {
        FeePolicy policy = new FeePolicy();
        policy.setTenantId(tenantId);
        policy.setProviderId(providerId);
        policy.setCutoffHours(cutoffHours);
        policy.setFeePercent(feePercent);
        return feePolicyRepository.save(policy);
    }

    /**
     * Books a slot directly (bypassing the HTTP booking flow) for tests
     * that only care about what happens *after* a booking already exists -
     * cancellation/reschedule/check-in. Mirrors what AppointmentWriter would
     * have produced.
     */
    protected Appointment createBookedAppointment(
            UUID tenantId, UUID slotId, UUID patientId, UUID providerId, UUID appointmentTypeId, UUID customerUserId) {
        Slot slot = slotRepository.findById(slotId).orElseThrow();
        slot.setStatus("booked");
        slotRepository.save(slot);

        Appointment appointment = new Appointment();
        appointment.setTenantId(tenantId);
        appointment.setSlotId(slotId);
        appointment.setPatientId(patientId);
        appointment.setProviderId(providerId);
        appointment.setAppointmentTypeId(appointmentTypeId);
        appointment.setChannel(customerUserId != null ? "patient_portal" : "front_desk");
        appointment.setCustomerUserId(customerUserId);
        appointment.setIdempotencyKey("fixture-" + UUID.randomUUID());
        appointment.setAppointmentRef(UUID.randomUUID().toString().substring(0, 6).toUpperCase(java.util.Locale.ROOT));
        return appointmentRepository.save(appointment);
    }

    // ---- auth builders: hand these straight to MockMvc's .with(...). ----

    protected RequestPostProcessor asPatient(String subject) {
        return jwtRequest(subject, "patient", null);
    }

    protected RequestPostProcessor asFrontDesk(String subject, String orgAlias) {
        return jwtRequest(subject, "front_desk", orgAlias);
    }

    protected RequestPostProcessor asProvider(String subject, String orgAlias) {
        return jwtRequest(subject, "provider", orgAlias);
    }

    protected RequestPostProcessor asClinicAdmin(String subject, String orgAlias) {
        return jwtRequest(subject, "clinic_admin", orgAlias);
    }

    protected RequestPostProcessor asPlatformAdmin(String subject) {
        return jwtRequest(subject, "platform_admin", null);
    }

    /** Pharmacy module (phase 20) - new realm role, same staff-JWT shape as front_desk/provider. */
    protected RequestPostProcessor asPharmacist(String subject, String orgAlias) {
        return jwtRequest(subject, "pharmacist", orgAlias);
    }

    /** Accounting/finance modules (phase 20, later sessions) - new realm role, same staff-JWT shape. */
    protected RequestPostProcessor asAccountant(String subject, String orgAlias) {
        return jwtRequest(subject, "accountant", orgAlias);
    }

    /** Lab module L1 (2026-10-02) - new realm role, same staff-JWT shape. */
    protected RequestPostProcessor asLabTechnician(String subject, String orgAlias) {
        return jwtRequest(subject, "lab_technician", orgAlias);
    }

    private RequestPostProcessor jwtRequest(String subject, String realmRole, String orgAlias) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .claim("realm_access", Map.of("roles", List.of(realmRole)))
                .claim("email", subject + "@example.test")
                .claim("name", subject)
                // Present as an empty array rather than omitted when there's
                // no org - that's the real "no organization" shape
                // extractOrgId sees (see TenantContextFilter), not a missing
                // claim.
                .claim("organization", orgAlias != null ? List.of(orgAlias) : List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        // .authorities(jwtAuthenticationConverter) would be the obvious call,
        // but JwtAuthenticationConverter converts to an
        // AbstractAuthenticationToken, not a Collection<GrantedAuthority> -
        // run the real converter and pull the authorities back out instead,
        // so tests exercise the actual ROLE_-prefix mapping rather than a
        // hand-rolled copy of it.
        return jwt().jwt(jwt).authorities(jwtAuthenticationConverter.convert(jwt).getAuthorities());
    }

    /**
     * Replaces the autoconfigured JwtDecoder, which is otherwise built by
     * calling out to KEYCLOAK_ISSUER_URI's OIDC discovery endpoint the
     * moment the context starts (JwtDecoders.fromIssuerLocation is eager,
     * unlike a jwk-set-uri-based decoder). Tests never call decode() at all
     * - the jwt() request post-processor above injects an already-built Jwt
     * straight into the SecurityContext - so this only needs to exist, not
     * do anything real.
     */
    @TestConfiguration
    static class NoNetworkJwtDecoderConfig {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException(
                        "Real JWT decoding should never run in tests - authenticate "
                                + "with AbstractIntegrationTest's asPatient/asFrontDesk/asProvider/"
                                + "asClinicAdmin/asPlatformAdmin helpers instead.");
            };
        }
    }
}
