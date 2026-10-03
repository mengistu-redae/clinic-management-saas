package com.clinicops.imaging;

import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.Locale;
import java.util.UUID;

/** Mirrors LabOrderRefGenerator exactly - a bare 6-char orderRef plus an optional clinic-initials+year+sequence clinicRef, "IMG-" prefixed instead of "LAB-". */
@Component
public class ImagingOrderRefGenerator {

    private static final int MAX_ATTEMPTS = 5;

    private final ImagingOrderRepository imagingOrderRepository;

    public ImagingOrderRefGenerator(ImagingOrderRepository imagingOrderRepository) {
        this.imagingOrderRepository = imagingOrderRepository;
    }

    public String nextOrderRef() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomHex(6);
            if (!imagingOrderRepository.existsByOrderRef(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique imaging order reference after " + MAX_ATTEMPTS + " attempts");
    }

    public String nextClinicRef(UUID tenantId, String clinicName) {
        String prefix = clinicInitials(clinicName);
        int year = Year.now().getValue();
        for (int seq = 1; seq <= 9999; seq++) {
            String candidate = "IMG-" + prefix + "-" + year + "-" + String.format("%04d", seq);
            if (!imagingOrderRepository.existsByTenantIdAndClinicRef(tenantId, candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique clinic reference for tenant " + tenantId);
    }

    private String clinicInitials(String clinicName) {
        if (clinicName == null || clinicName.isBlank()) {
            return "CL";
        }
        StringBuilder initials = new StringBuilder();
        for (String word : clinicName.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                initials.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return initials.isEmpty() ? "CL" : initials.substring(0, Math.min(initials.length(), 4));
    }

    private String randomHex(int length) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, length).toUpperCase(Locale.ROOT);
    }
}
