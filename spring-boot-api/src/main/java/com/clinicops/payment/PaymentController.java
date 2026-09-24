package com.clinicops.payment;

import com.clinicops.paymentgateway.PaymentGatewayException;
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

/**
 * Owner-agnostic - a refund is addressed by the payment's own id, not
 * nested under /api/appointments/.../ or /api/lab-orders/.../ the way
 * recording a payment is, since RefundService.refund only ever needs the
 * payment row itself. Same 3-role read / 2-role write gate as
 * AppointmentPaymentController/LabOrderPaymentController. No idempotency
 * key - unlike booking, a refund is always a distinct, deliberate action,
 * never an accidental double-submit risk.
 */
@RestController
public class PaymentController {

    private final RefundService refundService;
    private final CurrentUserService currentUserService;

    public PaymentController(RefundService refundService, CurrentUserService currentUserService) {
        this.refundService = refundService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/payments/{id}/refunds")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN', 'PROVIDER')")
    public List<Refund> refunds(@PathVariable UUID id) {
        return refundService.listForPayment(TenantContext.require(), id);
    }

    @PostMapping("/api/payments/{id}/refund")
    @PreAuthorize("hasAnyRole('FRONT_DESK', 'CLINIC_ADMIN')")
    public Refund refund(@PathVariable UUID id, @Valid @RequestBody CreateRefundRequest request, @AuthenticationPrincipal Jwt jwt) {
        UUID tenantId = TenantContext.require();
        UUID refundedBy = currentUserService.resolveInternalUserId(jwt);
        return refundService.refund(tenantId, id, request, refundedBy);
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }

    @ExceptionHandler(RefundExceedsPaymentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleExceedsPayment(RefundExceedsPaymentException e) {
        return e.getMessage();
    }

    @ExceptionHandler(PaymentGatewayException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public String handleGatewayError(PaymentGatewayException e) {
        return e.getMessage();
    }
}
