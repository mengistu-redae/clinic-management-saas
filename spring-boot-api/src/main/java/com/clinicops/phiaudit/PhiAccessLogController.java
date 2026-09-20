package com.clinicops.phiaudit;

import com.clinicops.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * `clinic_admin` only - reviewing who accessed patient data is itself a
 * privileged action, not something front_desk/provider need for their own
 * work. Most-recent-200 rows, same "no pagination framework anywhere in
 * this app" simplicity as every other list endpoint - a real deployment
 * outgrowing 200 rows of interest is a later problem, not a v1 one.
 */
@RestController
public class PhiAccessLogController {

    private final PhiAccessLogRepository repository;

    public PhiAccessLogController(PhiAccessLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/clinic/phi-access-log")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public List<PhiAccessLog> phiAccessLog(@RequestParam(required = false) UUID patientId) {
        UUID tenantId = TenantContext.require();
        return patientId == null
                ? repository.findTop200ByTenantIdOrderByCreatedAtDesc(tenantId)
                : repository.findTop200ByTenantIdAndPatientIdOrderByCreatedAtDesc(tenantId, patientId);
    }
}
