package com.clinicops.support;

import com.clinicops.appointment.Appointment;
import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.appointmenttype.AppointmentType;
import com.clinicops.appointmenttype.AppointmentTypeRepository;
import com.clinicops.clinic.Clinic;
import com.clinicops.clinic.ClinicRepository;
import com.clinicops.clinicsettings.ClinicSettingsRepository;
import com.clinicops.encounter.Encounter;
import com.clinicops.encounter.EncounterRepository;
import com.clinicops.feepolicy.FeePolicy;
import com.clinicops.feepolicy.FeePolicyRepository;
import com.clinicops.labrate.LabTestRate;
import com.clinicops.labrate.LabTestRateRepository;
import com.clinicops.patient.Patient;
import com.clinicops.patient.PatientRepository;
import com.clinicops.provider.Provider;
import com.clinicops.provider.ProviderRepository;
import com.clinicops.provider.ProviderWorkingHours;
import com.clinicops.provider.ProviderWorkingHoursRepository;
import com.clinicops.room.Room;
import com.clinicops.room.RoomRepository;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import com.clinicops.user.AppUser;
import com.clinicops.user.AppUserRepository;
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
    protected RoomRepository roomRepository;

    @Autowired
    protected ClinicSettingsRepository clinicSettingsRepository;

    @Autowired
    protected EncounterRepository encounterRepository;

    @Autowired
    protected LabTestRateRepository labTestRateRepository;

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
