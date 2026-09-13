package com.clinicops.laborder;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bound via @ConfigurationProperties, not @Value - a YAML sequence has no
 * single property at the bare key (Boot stores it as indexed
 * restricted-tests[0], [1], ...). Requires @ConfigurationPropertiesScan on
 * ClinicManagementApplication - the first @ConfigurationProperties class in
 * this app, same "silently inert without the enabling annotation" shape as
 * @EnableMethodSecurity/@EnableScheduling before it.
 *
 * Each entry compiles as a case-insensitive regex, falling back to a
 * literal-substring match if it isn't valid regex - so a clinic admin can
 * write either a precise pattern or just a plain word/code.
 */
@ConfigurationProperties(prefix = "clinic.lab")
public class RestrictedTestsProperties {

    private List<String> restrictedTests = List.of();

    public List<String> getRestrictedTests() {
        return restrictedTests;
    }

    public void setRestrictedTests(List<String> restrictedTests) {
        this.restrictedTests = restrictedTests;
    }

    /** True if testCode matches any configured restricted-test entry (regex, or literal substring if the entry isn't valid regex). */
    public boolean isRestricted(String testCode) {
        if (testCode == null) {
            return false;
        }
        for (String entry : restrictedTests) {
            if (matches(entry, testCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String entry, String testCode) {
        try {
            Pattern pattern = Pattern.compile(entry, Pattern.CASE_INSENSITIVE);
            if (pattern.matcher(testCode).find()) {
                return true;
            }
        } catch (PatternSyntaxException e) {
            return testCode.toLowerCase(java.util.Locale.ROOT).contains(entry.toLowerCase(java.util.Locale.ROOT));
        }
        return false;
    }
}
