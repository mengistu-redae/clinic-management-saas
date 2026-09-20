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
 * Deliberately a plain RestClient rather than the `org.keycloak:keycloak
 * -admin-client` library - that library pulls in its own RESTEasy client
 * and Jackson versions that risk classpath conflicts with Spring's own
 * stack, not worth it for a handful of HTTP calls (see the kickoff spec's
 * exact reasoning, quoted in CLAUDE.md's phase 6 write-up).
 *
 * Realm is hardcoded to "clinic" - unlike the reference project (which
 * templated it), this app only has the one realm.
 */
@Service
public class KeycloakOrganizationClient {

    private static final String REALM = "clinic";

    private final RestClient restClient;
    private final KeycloakAdminTokenProvider adminTokenProvider;

    public KeycloakOrganizationClient(
            @Value("${clinicops.keycloak-admin.base-url}") String baseUrl,
            KeycloakAdminTokenProvider adminTokenProvider) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminTokenProvider = adminTokenProvider;
    }

    /**
     * Returns the new org's Keycloak-internal id - callers should NOT
     * persist it. `clinics.keycloak_org_id` stores the alias, since
     * Keycloak's built-in oidc-organization-membership-mapper puts the
     * alias, not the id, in a token's `organization` claim (see
     * TenantContextFilter.extractOrgId's javadoc).
     */
    public String createOrganization(String name, String alias, String domain) {
        String token = adminTokenProvider.fetchToken();
        Map<String, Object> body = Map.of(
                "name", name,
                "alias", alias,
                "enabled", true,
                "domains", List.of(Map.of("name", domain, "verified", true)));

        URI location;
        try {
            location = restClient.post()
                    .uri("/admin/realms/{realm}/organizations", REALM)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Failed to create Keycloak organization '" + alias + "'", e);
        }

        if (location == null) {
            throw new KeycloakAdminException(
                    "Keycloak accepted the organization create request but returned no Location header");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * Mirrors create-demo-clinic.sh's own manual membership-add call. Takes
     * the Keycloak-internal org id (createOrganization's return value), not
     * the alias - the members endpoint is keyed by id, unlike the
     * organization claim a token carries.
     *
     * The body must be a bare JSON string ("<userId>"), not a JSON object -
     * built by hand rather than passed as a plain Java String, since
     * RestClient's string message converter would otherwise write it
     * unquoted (invalid JSON) despite the declared content type.
     */
    public void addMember(String keycloakOrgId, String userId) {
        String token = adminTokenProvider.fetchToken();
        String jsonBody = "\"" + userId + "\"";
        try {
            restClient.post()
                    .uri("/admin/realms/{realm}/organizations/{orgId}/members", REALM, keycloakOrgId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonBody)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new KeycloakAdminException(
                    "Failed to add user " + userId + " as a member of organization " + keycloakOrgId, e);
        }
    }
}
