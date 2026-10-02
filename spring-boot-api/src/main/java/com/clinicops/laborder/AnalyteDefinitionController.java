package com.clinicops.laborder;

import com.clinicops.tenant.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The analyte catalog for a test_code - same CRUD shape as LabRateController
 * (config, real hard delete - a missing definition just means "this test
 * stays unstructured," a well-defined fallback, same reasoning FeePolicy/
 * LabRate already use for their own hard deletes).
 *
 * Write stays clinic_admin-only, matching LabRate/FeePolicy's own "config
 * setup is an admin job" convention - a judgment call, not an explicitly
 * pinned decision (flagged here rather than assumed silently). Read is
 * widened to lab_technician too, since they're the ones actually entering
 * results against these ranges day to day.
 */
@RestController
public class AnalyteDefinitionController {

    private final AnalyteDefinitionRepository analyteDefinitionRepository;

    public AnalyteDefinitionController(AnalyteDefinitionRepository analyteDefinitionRepository) {
        this.analyteDefinitionRepository = analyteDefinitionRepository;
    }

    @GetMapping("/api/clinic/analyte-definitions")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'LAB_TECHNICIAN')")
    public List<AnalyteDefinition> analyteDefinitions(@RequestParam(required = false) String testCode) {
        UUID tenantId = TenantContext.require();
        if (testCode != null && !testCode.isBlank()) {
            return analyteDefinitionRepository.findAllByTenantIdAndTestCodeOrderByDisplayOrder(tenantId, testCode);
        }
        return analyteDefinitionRepository.findAllByTenantIdOrderByTestCodeAscDisplayOrderAsc(tenantId);
    }

    @GetMapping("/api/clinic/analyte-definitions/{id}")
    @PreAuthorize("hasAnyRole('CLINIC_ADMIN', 'LAB_TECHNICIAN')")
    public AnalyteDefinition analyteDefinition(@PathVariable UUID id) {
        return analyteDefinitionRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Analyte definition not found: " + id));
    }

    @PostMapping("/api/clinic/analyte-definitions")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public AnalyteDefinition createAnalyteDefinition(@Valid @RequestBody CreateAnalyteDefinitionRequest request) {
        UUID tenantId = TenantContext.require();
        if (analyteDefinitionRepository.existsByTenantIdAndTestCodeAndAnalyteName(tenantId, request.testCode(), request.analyteName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An analyte definition for " + request.testCode() + "/" + request.analyteName() + " already exists");
        }
        AnalyteDefinition definition = new AnalyteDefinition();
        definition.setTenantId(tenantId);
        definition.setTestCode(request.testCode());
        definition.setAnalyteName(request.analyteName());
        definition.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : 0);
        definition.setUnit(request.unit());
        definition.setNormalRangeLow(request.normalRangeLow());
        definition.setNormalRangeHigh(request.normalRangeHigh());
        definition.setNormalRangeText(request.normalRangeText());
        definition.setCriticalRangeLow(request.criticalRangeLow());
        definition.setCriticalRangeHigh(request.criticalRangeHigh());
        return analyteDefinitionRepository.save(definition);
    }

    @PostMapping("/api/clinic/analyte-definitions/{id}/update")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public AnalyteDefinition updateAnalyteDefinition(@PathVariable UUID id, @RequestBody UpdateAnalyteDefinitionRequest request) {
        AnalyteDefinition definition = analyteDefinitionRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Analyte definition not found: " + id));
        if (request.displayOrder() != null) {
            definition.setDisplayOrder(request.displayOrder());
        }
        if (request.unit() != null) {
            definition.setUnit(request.unit());
        }
        if (request.normalRangeLow() != null) {
            definition.setNormalRangeLow(request.normalRangeLow());
        }
        if (request.normalRangeHigh() != null) {
            definition.setNormalRangeHigh(request.normalRangeHigh());
        }
        if (request.normalRangeText() != null) {
            definition.setNormalRangeText(request.normalRangeText());
        }
        if (request.criticalRangeLow() != null) {
            definition.setCriticalRangeLow(request.criticalRangeLow());
        }
        if (request.criticalRangeHigh() != null) {
            definition.setCriticalRangeHigh(request.criticalRangeHigh());
        }
        return analyteDefinitionRepository.save(definition);
    }

    /** Real delete - config, not a clinical record; already-entered AnalyteResults keep their own snapshotted name/unit/range regardless. */
    @PostMapping("/api/clinic/analyte-definitions/{id}/delete")
    @PreAuthorize("hasRole('CLINIC_ADMIN')")
    public AnalyteDefinition deleteAnalyteDefinition(@PathVariable UUID id) {
        AnalyteDefinition definition = analyteDefinitionRepository.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new NoSuchElementException("Analyte definition not found: " + id));
        analyteDefinitionRepository.delete(definition);
        return definition;
    }

    @ExceptionHandler(NoSuchElementException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFound(NoSuchElementException e) {
        return e.getMessage();
    }
}
