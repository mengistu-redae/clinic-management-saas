package com.clinicops.payment;

import com.clinicops.appointment.AppointmentRepository;
import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Recording a payment is a deliberate, separate staff action - booking/cancelling an appointment never creates or voids one. No delete. */
@RestController
public class AppointmentPaymentController {

    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentUserService currentUserService;

    public AppointmentPaymentController(
            AppointmentRepository appointmentRepository, PaymentRepository paymentRepository, CurrentUserService currentUserService) {
        this.appointmentRepository = appointmentRepository;
        this.paymentRepository = paymentRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/appointments/{id}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public List<Payment> payments(@PathVariable UUID id) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);
        return paymentRepository.findAllByAppointmentIdAndTenantId(id, tenantId);
    }

    @PostMapping("/api/appointments/{id}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Payment recordPayment(@PathVariable UUID id, @Valid @RequestBody CreatePaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedAppointment(id, tenantId);

        Payment payment = new Payment();
        payment.setTenantId(tenantId);
        payment.setAppointmentId(id);
        payment.setAmount(request.amount());
        payment.setMethod(request.method());
        payment.setTransactionId(request.transactionId());
        payment.setRecordedBy(currentUserService.resolveInternalUserId(jwt));
        return paymentRepository.save(payment);
    }

    private void requireOwnedAppointment(UUID appointmentId, UUID tenantId) {
        appointmentRepository.findByIdAndTenantId(appointmentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Appointment not found: " + appointmentId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
