package com.clinicops.platform;

/** Maps to 502 in PlatformController - the failure is genuinely upstream, in Keycloak, not a client error. */
public class KeycloakAdminException extends RuntimeException {
    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
