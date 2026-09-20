package com.clinicops.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The user-provisioning half of the Keycloak admin API, split from
 * KeycloakOrganizationClient by API area (organizations vs. users) rather
 * than bundled into one god-client. Used only by ClinicProvisioningService's
 * optional "create an initial clinic_admin login" step when a platform_admin
 * supplies adminEmail on clinic onboarding - closes the "no initial
 * clinic_admin user provisioning" gap noted in CLAUDE.md's phase 6 write-up.
 * Shares KeycloakAdminTokenProvider with KeycloakOrganizationClient - that
 * bean's own javadoc already anticipated exactly this second consumer.
 */
@Service
public class KeycloakUserProvisioningClient {

    private static final String REALM = "clinic";

    private final RestClient restClient;
    private final KeycloakAdminTokenProvider adminTokenProvider;

    public KeycloakUserProvisioningClient(
            @Value("${clinicops.keycloak-admin.base-url}") String baseUrl,
            KeycloakAdminTokenProvider adminTokenProvider) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminTokenProvider = adminTokenProvider;
    }

    /**
     * Returns the new user's Keycloak-internal id. Username is the email -
     * this realm has no separate staff username concept anywhere else in the
     * app. emailVerified is left false and no credential is set here -
     * ClinicProvisioningService sets a temporary password separately once
     * the user exists.
     */
    public String createUser(String email, String firstName, String lastName) {
        String token = adminTokenProvider.fetchToken();
        Map<String, Object> body = Map.of(
                "username", email,
                "email", email,
                "enabled", true,
                "emailVerified", false,
                "firstName", firstName,
                "lastName", lastName);

        URI location;
        try {
            location = restClient.post()
                    .uri("/admin/realms/{realm}/users", REALM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to create Keycloak user '" + email + "'", e);
        }

        if (location == null) {
            throw new KeycloakAdminException("Keycloak accepted the user create request but returned no Location header");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Realm-role assignment needs the role's own id, not just its name - a
     * separate lookup call, same two-step shape the Keycloak admin API
     * requires for this endpoint.
     */
    public void assignRealmRole(String userId, String roleName) {
        String token = adminTokenProvider.fetchToken();
        Map<String, Object> role;
        try {
            role = restClient.get()
                    .uri("/admin/realms/{realm}/roles/{roleName}", REALM, roleName)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to look up Keycloak realm role '" + roleName + "'", e);
        }
        if (role == null) {
            throw new KeycloakAdminException("Keycloak realm role '" + roleName + "' was not found");
        }

        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/users/{userId}/role-mappings/realm", REALM, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of(role))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to assign realm role '" + roleName + "' to user " + userId, e);
        }
    }

    /**
     * temporary=true forces the user to set their own password on first
     * login. No SMTP is configured for this realm in local dev (see
     * CLAUDE.md's known gaps - there's no real email sender anywhere in this
     * app yet), so this is the only delivery mechanism: the caller hands the
     * generated value back to the platform_admin once, out of band.
     */
    public void setTemporaryPassword(String userId, String temporaryPassword) {
        String token = adminTokenProvider.fetchToken();
        Map<String, Object> credential = Map.of(
                "type", "password",
                "value", temporaryPassword,
                "temporary", true);
        try {
            restClient.put()
                    .uri("/admin/realms/{realm}/users/{userId}/reset-password", REALM, userId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(credential)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to set a temporary password for user " + userId, e);
        }
    }
}
