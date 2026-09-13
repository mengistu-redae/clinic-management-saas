package com.clinicops.appointment;

import com.clinicops.patient.PatientProvisioningService;
import com.clinicops.provider.CurrentProviderService;
import com.clinicops.scheduling.Slot;
import com.clinicops.scheduling.SlotRepository;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Handles both authenticated booking channels, exactly like the reference
 * project's BookingController:
 *  - patient_portal: JWT role = patient, no org.
 *  - front_desk: JWT role = front_desk/clinic_admin, org set.
 * Channel is always decided from the JWT role here, never from anything the
 * client sends.
 */
@RestController
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final CurrentUserService currentUserService;
    private final PatientProvisioningService patientProvisioningService;
    private final AppointmentRepository appointmentRepository;
    private final SlotRepository slotRepository;
    private final AppointmentSeriesService appointmentSeriesService;
    private final CurrentProviderService currentProviderService;

    public AppointmentController(
            AppointmentService appointmentService,
            CurrentUserService currentUserService,
            PatientProvisioningService patientProvisioningService,
            AppointmentRepository appointmentRepository,
            SlotRepository slotRepository,
            AppointmentSeriesService appointmentSeriesService,
            CurrentProviderService currentProviderService) {
        this.appointmentService = appointmentService;
        this.currentUserService = currentUserService;
        this.patientProvisioningService = patientProvisioningService;
        this.appointmentRepository = appointmentRepository;
        this.slotRepository = slotRepository;
        this.appointmentSeriesService = appointmentSeriesService;
        this.currentProviderService = currentProviderService;
    }

    @PostMapping("/api/appointments")
    @PreAuthorize("hasAnyRole('PATIENT', 'FRONT_DESK', 'CLINIC_ADMIN')")
    public Appointment createAppointment(@Valid @RequestBody CreateAppointmentRequest request, @AuthenticationPrincipal Jwt jwt) {
        boolean isPatient = hasRole(jwt, "PATIENT");
        String channel = isPatient ? "patient_portal" : "front_desk";

        UUID patientId;
        UUID customerUserId = null;
        if (isPatient) {
            var patient = patientProvisioningService.resolveForPortalUser(tenantIdOfSlot(request.slotId()), jwt);
            patientId = patient.getId();
            customerUserId = currentUserService.resolveInternalUserId(jwt);
        } else {
            if (request.patientId() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "patientId is required for front_desk bookings");
            }
            patientId = request.patientId();
        }

        var command = AppointmentBookingCommand.singleBooking(
                request.slotId(), request.providerId(), request.appointmentTypeId(), channel,
                TenantContext.get(), patientId, customerUserId, null, null,
                jwt.getClaimAsString("email"), request.idempotencyKey());

        return appointmentService.createAppointment(command);
    }

    /**
     * No account, no session, no JWT at all - see SecurityConfig's
     * permitAll() list. patientId/customerUserId/callerTenantId are always
     * null here since there's no principal to resolve them from.
     */
    @PostMapping("/api/appointments/guest")
    public Appointment createGuestAppointment(@Valid @RequestBody CreateGuestAppointmentRequest request) {
        var command = AppointmentBookingCommand.singleBooking(
                request.slotId(), request.providerId(), request.appointmentTypeId(), "guest",
                null, null, null, request.contactName(), request.contactPhone(),
                request.contactEmail(), request.idempotencyKey());

        return appointmentService.createAppointment(command);
    }

    /** Two-factor public tracking - ref + phone must match, otherwise 404 identically to an unknown ref. */
    @GetMapping("/api/appointments/track/{appointmentRef}")
    public AppointmentTrackingView trackAppointment(@PathVariable String appointmentRef, @RequestParam String phone) {
        return appointmentService.trackByRefAndPhone(appointmentRef, phone);
    }

    @GetMapping("/api/my-appointments")
    @PreAuthorize("hasRole('PATIENT')")
    public List<Appointment> myAppointments(@AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return appointmentRepository.findAllByCustomerUserId(customerUserId);
    }

    @GetMapping("/api/my-appointments/{id}")
    @PreAuthorize("hasRole('PATIENT')")
    public Appointment myAppointment(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID customerUserId = currentUserService.resolveInternalUserId(jwt);
        return appointmentRepository.findByIdAndCustomerUserId(id, customerUserId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + id));
    }

    @GetMapping("/api/appointments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public List<Appointment> appointments() {
        return appointmentRepository.findAllByTenantId(TenantContext.require());
    }

    @GetMapping("/api/appointments/{id}")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public Appointment appointment(@PathVariable UUID id) {
        return appointmentRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + id));
    }

    /**
     * A provider's own worklist - scoped to the caller's own resolved
     * Provider.id (via CurrentProviderService), not the tenant-wide
     * GET /api/appointments front_desk/clinic_admin use. Day boundaries use
     * ZoneOffset.UTC, matching AvailabilityController/SlotGenerationService's
     * existing day-math convention - no new timezone concept introduced.
     */
    @GetMapping("/api/my-schedule")
    @PreAuthorize("hasRole('PROVIDER')")
    public List<Appointment> mySchedule(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID providerId = currentProviderService.resolveProviderId(jwt, tenantId);
        LocalDate scheduleDate = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        Instant dayStart = scheduleDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayEnd = scheduleDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return appointmentRepository.findProviderSchedule(tenantId, providerId, dayStart, dayEnd);
    }

    /**
     * Books a bounded recurring series - staff-only, never patient_portal
     * (a patient still self-books one appointment at a time). Returns 201
     * if at least one occurrence was created, 409 if the very first
     * occurrence's slot was unavailable (nothing created at all) - see
     * AppointmentSeriesResult's javadoc for why partial success is allowed.
     */
    @PostMapping("/api/appointments/series")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public ResponseEntity<AppointmentSeriesResult> createSeries(
            @Valid @RequestBody CreateAppointmentSeriesRequest request, @AuthenticationPrincipal Jwt jwt) {
        var result = appointmentSeriesService.createSeries(
                request, TenantContext.require(), jwt.getClaimAsString("email"));
        HttpStatus status = result.created().isEmpty() ? HttpStatus.CONFLICT : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * patient_portal booking needs a tenantId before a Patient can be
     * resolved/provisioned, but TenantContext is empty for a patient token
     * (no org claim) - the slot itself carries the real tenant, so this
     * does a lightweight lookup rather than trusting anything client-side.
     * AppointmentService re-validates the slot properly afterwards; this is
     * only used to scope the patient-provisioning step correctly.
     */
    private UUID tenantIdOfSlot(UUID slotId) {
        return slotRepository.findById(slotId)
                .map(Slot::getTenantId)
                .orElseThrow(() -> new NoSuchElementException("Slot not found: " + slotId));
    }

    private boolean hasRole(Jwt jwt, String role) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        var roles = (List<String>) realmAccess.get("roles");
        return roles != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase(role));
    }

    @ExceptionHandler(SlotConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleSlotConflict(SlotConflictException e) {
        return e.getMessage();
    }

    @ExceptionHandler(TenantMismatchException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String handleTenantMismatch(TenantMismatchException e) {
        return e.getMessage();
    }

    @ExceptionHandler(ClinicInactiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public String handleClinicInactive(ClinicInactiveException e) {
        return e.getMessage();
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
