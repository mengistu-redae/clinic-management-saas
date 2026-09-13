package com.clinicops.payment;

import com.clinicops.laborder.LabOrderRepository;
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

/** Separate from AppointmentPaymentController - a different owner column and base path nesting, same shared Payment entity/CreatePaymentRequest shape. No delete. */
@RestController
public class LabOrderPaymentController {

    private final LabOrderRepository labOrderRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentUserService currentUserService;

    public LabOrderPaymentController(
            LabOrderRepository labOrderRepository, PaymentRepository paymentRepository, CurrentUserService currentUserService) {
        this.labOrderRepository = labOrderRepository;
        this.paymentRepository = paymentRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/lab-orders/{orderId}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public List<Payment> payments(@PathVariable UUID orderId) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);
        return paymentRepository.findAllByLabOrderIdAndTenantId(orderId, tenantId);
    }

    @PostMapping("/api/lab-orders/{orderId}/payments")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Payment recordPayment(@PathVariable UUID orderId, @Valid @RequestBody CreatePaymentRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        requireOwnedLabOrder(orderId, tenantId);

        Payment payment = new Payment();
        payment.setTenantId(tenantId);
        payment.setLabOrderId(orderId);
        payment.setAmount(request.amount());
        payment.setMethod(request.method());
        payment.setTransactionId(request.transactionId());
        payment.setRecordedBy(currentUserService.resolveInternalUserId(jwt));
        return paymentRepository.save(payment);
    }

    private void requireOwnedLabOrder(UUID labOrderId, UUID tenantId) {
        labOrderRepository.findByIdAndTenantId(labOrderId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Lab order not found: " + labOrderId));
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
