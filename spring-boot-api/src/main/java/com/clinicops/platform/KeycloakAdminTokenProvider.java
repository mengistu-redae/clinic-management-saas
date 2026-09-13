package com.clinicops.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * Logs in as the Keycloak admin (master realm's built-in admin-cli client,
 * Resource Owner Password Credentials grant) - the same two-call flow
 * infra/keycloak/create-demo-clinic.sh already does by hand, just from the
 * app. Shared by KeycloakOrganizationClient; kept as its own bean since a
 * future platform-admin action (e.g. a user-provisioning endpoint) would
 * need the identical token.
 */
@Service
public class KeycloakAdminTokenProvider {

    private final RestClient restClient;
    private final String adminUsername;
    private final String adminPassword;

    public KeycloakAdminTokenProvider(
            @Value("${clinicops.keycloak-admin.base-url}") String baseUrl,
            @Value("${clinicops.keycloak-admin.admin-username}") String adminUsername,
            @Value("${clinicops.keycloak-admin.admin-password}") String adminPassword) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    public String fetchToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", "admin-cli");
        form.add("username", adminUsername);
        form.add("password", adminPassword);
        form.add("grant_type", "password");

        Map<String, Object> tokenResponse;
        try {
            tokenResponse = restClient.post()
                    .uri("/realms/master/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            throw new KeycloakAdminException("Could not authenticate as the Keycloak admin", e);
        }

        Object accessToken = tokenResponse != null ? tokenResponse.get("access_token") : null;
        if (accessToken == null) {
            throw new KeycloakAdminException("Keycloak admin login response had no access_token");
        }
        return accessToken.toString();
    }
}
