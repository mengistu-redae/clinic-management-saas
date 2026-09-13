package com.clinicops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling: without it, @Scheduled methods (NoShowScheduler) are
// silently never invoked - Spring Boot does not turn this on automatically
// just because a bean has a @Scheduled method, same class of gotcha as
// @EnableMethodSecurity for @PreAuthorize (see SecurityConfig).
// @ConfigurationPropertiesScan: without it, @ConfigurationProperties classes
// (RestrictedTestsProperties, phase 7) are never registered as beans -
// same class of gotcha again, not automatic just because the annotation
// exists on a class.
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class ClinicManagementApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClinicManagementApplication.class, args);
    }
}
