package com.clinicops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the resource-server {@link JwtDecoder} by hand instead of letting
 * Spring Boot autoconfigure one straight off a single {@code issuer-uri}
 * property, because - confirmed live 2026-09-12 against a real Keycloak 26
 * login through node-bff - this Keycloak (with {@code KC_HOSTNAME_STRICT:
 * false}) pins every token's {@code iss} claim, for a whole authorization-code
 * flow, to whichever host the *browser's* original request to the authorize
 * endpoint used (the public URL), not the host node-bff's own server-to-server
 * calls use to reach Keycloak (the internal, container-network URL). Those
 * two are genuinely different strings here (see {@code docker-compose.yml}:
 * {@code KEYCLOAK_ISSUER_URI} vs {@code KEYCLOAK_ISSUER_PUBLIC_URI}), so one
 * property can't serve both purposes:
 * <ul>
 *   <li>fetching the JWKS to verify a token's signature needs a URL this
 *       container can actually reach ({@code KEYCLOAK_ISSUER_URI} - the
 *       internal one; this container has no route to "localhost:8080",
 *       which from inside it means itself, not the Docker host);</li>
 *   <li>validating the token's {@code iss} claim needs to match what
 *       Keycloak actually stamped there ({@code KEYCLOAK_ISSUER_PUBLIC_URI} -
 *       confirmed to be the public one, per the finding above).</li>
 * </ul>
 * {@code JwtDecoders.fromIssuerLocation(issuerUri)} (Spring Boot's default,
 * off the {@code issuer-uri} property) couples both to the same one string,
 * which can't satisfy both needs at once - hence this explicit decoder:
 * fetch from the internal JWKS endpoint, validate against the public issuer.
 *
 * {@code @Profile("!test")} so this bean doesn't exist at all under the
 * "test" profile - {@code AbstractIntegrationTest}'s own test-only
 * {@code JwtDecoder} (which never does any real network call) is the sole
 * candidate there instead. A plain {@code @ConditionalOnMissingBean} would
 * work too when this is the only other candidate, but which of two
 * ordinary (non-autoconfiguration) {@code @Configuration} classes gets
 * processed first isn't guaranteed the way Boot's own deferred
 * autoconfiguration ordering is - {@code @Profile} sidesteps that instead of
 * relying on it.
 */
@Configuration
public class JwtDecoderConfig {

    @Bean
    @Profile("!test")
    public JwtDecoder jwtDecoder(
            @Value("${clinicops.keycloak.internal-issuer-uri}") String internalIssuerUri,
            @Value("${clinicops.keycloak.public-issuer}") String publicIssuer) {
        String jwkSetUri = internalIssuerUri + "/protocol/openid-connect/certs";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(publicIssuer));
        return decoder;
    }
}
