package com.clinicops.laborder;

import com.clinicops.tenant.TenantContext;
import com.clinicops.user.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Lab module L4 - basic QC logging, reviewable by lab_technician (+
 * clinic_admin override, same as every other write-gated lab endpoint).
 * Deliberately {@code lab_technician}+{@code clinic_admin} only, not
 * {@code provider} - this is internal lab-operations record-keeping, not
 * clinical data a provider needs to see, unlike a critical analyte result
 * (L3) which genuinely requires the ordering clinician's own attention.
 *
 * Flag-only, no enforcement (the pinned fork's recommended option,
 * 2026-10-02): {@code pass} is computed and stored, but nothing anywhere
 * else in this app (AnalyteResultService.enterResults included) ever reads
 * it to block a new result - a failed run is just visible on this log.
 */
@RestController
public class QcRunController {

    private final QcRunRepository qcRunRepository;
    private final CurrentUserService currentUserService;

    public QcRunController(QcRunRepository qcRunRepository, CurrentUserService currentUserService) {
        this.qcRunRepository = qcRunRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/lab-qc-runs")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public List<QcRun> qcRuns(@RequestParam(required = false) String instrumentIdentifier) {
        UUID tenantId = TenantContext.require();
        return instrumentIdentifier == null || instrumentIdentifier.isBlank()
                ? qcRunRepository.findAllByTenantIdOrderByPerformedAtDesc(tenantId)
                : qcRunRepository.findAllByTenantIdAndInstrumentIdentifierOrderByPerformedAtDesc(tenantId, instrumentIdentifier);
    }

    @PostMapping("/api/lab-qc-runs")
    @PreAuthorize("hasAnyRole('LAB_TECHNICIAN', 'CLINIC_ADMIN')")
    public QcRun recordQcRun(@Valid @RequestBody CreateQcRunRequest request, @AuthenticationPrincipal Jwt jwt) {
        BigDecimal observed;
        try {
            observed = new BigDecimal(request.observedValue().trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "observedValue must be a numeric value");
        }
        if (request.expectedRangeLow().compareTo(request.expectedRangeHigh()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expectedRangeLow must not exceed expectedRangeHigh");
        }

        QcRun qcRun = new QcRun();
        qcRun.setTenantId(TenantContext.require());
        qcRun.setInstrumentIdentifier(request.instrumentIdentifier());
        qcRun.setAnalyteName(request.analyteName());
        qcRun.setControlMaterialLot(request.controlMaterialLot());
        qcRun.setExpectedRangeLow(request.expectedRangeLow());
        qcRun.setExpectedRangeHigh(request.expectedRangeHigh());
        qcRun.setObservedValue(request.observedValue());
        qcRun.setPass(observed.compareTo(request.expectedRangeLow()) >= 0 && observed.compareTo(request.expectedRangeHigh()) <= 0);
        qcRun.setPerformedBy(currentUserService.resolveInternalUserId(jwt));
        return qcRunRepository.save(qcRun);
    }
}
