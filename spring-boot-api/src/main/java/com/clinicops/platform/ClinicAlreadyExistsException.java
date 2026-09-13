package com.clinicops.platform;

/** Maps to 409 in PlatformController - thrown before ever calling Keycloak, so a duplicate alias never costs an admin-API round trip. */
public class ClinicAlreadyExistsException extends RuntimeException {
    public ClinicAlreadyExistsException(String message) {
        super(message);
    }
}
