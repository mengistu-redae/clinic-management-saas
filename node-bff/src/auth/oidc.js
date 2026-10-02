'use strict';

const { Issuer } = require('openid-client');

const DISCOVERY_RETRY_DELAY_MS = 3000;
// ~1 minute of retries - Keycloak's `start-dev --import-realm` is slow on a
// cold start, and docker-compose's depends_on only waits for the container
// to start, not for its HTTP listener + realm import to actually finish.
const DISCOVERY_MAX_ATTEMPTS = 20;

/**
 * Discovers Keycloak's OIDC metadata and builds a confidential client.
 *
 * KEYCLOAK_ISSUER vs KEYCLOAK_ISSUER_PUBLIC: Keycloak runs with
 * KC_HOSTNAME_STRICT=false, so it reports whatever host a request came in
 * on. This discovery call is made from inside the docker network, so it
 * gets back "http://keycloak:8080/..." endpoints - correct for the calls
 * *we* make server-to-server (token, jwks, userinfo), but wrong for
 * authorization_endpoint and end_session_endpoint, which are redirect
 * targets we hand to the user's browser. The browser can't resolve
 * "keycloak" - only KEYCLOAK_ISSUER_PUBLIC's host (localhost:8080, published
 * by docker-compose), so those two get rewritten below.
 *
 * `issuer.metadata.issuer` itself also gets rewritten to the public value,
 * not left as the internal one discovery returned - see the comment on the
 * `issuer:` field below for why (a real, counter-intuitive Keycloak
 * behavior, confirmed live against a running instance).
 */
async function buildOidcClient() {
  const internalIssuer = await discoverWithRetry();

  const internalOrigin = new URL(process.env.KEYCLOAK_ISSUER).origin;
  const publicOrigin = new URL(process.env.KEYCLOAK_ISSUER_PUBLIC).origin;
  const toPublic = (url) => (url ? url.replace(internalOrigin, publicOrigin) : url);

  const issuer = new Issuer({
    ...internalIssuer.metadata,
    authorization_endpoint: toPublic(internalIssuer.metadata.authorization_endpoint),
    end_session_endpoint: toPublic(internalIssuer.metadata.end_session_endpoint),
    // `issuer` itself also needs the public value, unlike every other
    // metadata field here - confirmed live 2026-09-12 against a real
    // Keycloak 26 login. It turns out Keycloak (with KC_HOSTNAME_STRICT=
    // false) doesn't compute the issuer per-request from each individual
    // call's own Host header; it pins the issuer, for the whole
    // authorization-code flow, to whichever host the *browser's* original
    // request to the authorize endpoint used - and reuses that same value
    // both for the RFC 9207 `iss` redirect parameter AND for the `iss` claim
    // stamped into the resulting ID/access tokens, even though node-bff's
    // own token-exchange call is made server-to-server via the internal
    // KEYCLOAK_ISSUER host. So every token this client will ever see in this
    // flow genuinely has iss = KEYCLOAK_ISSUER_PUBLIC, not KEYCLOAK_ISSUER -
    // openid-client's ID-token validation (unlike the RFC 9207 check, this
    // one has no "not supported" opt-out; it's a mandatory OIDC check) will
    // reject anything else. `token_endpoint`/`jwks_uri` etc. are left as
    // their real (internal, network-reachable) discovered values below -
    // `issuer` is just an identity string checked against token claims, it
    // drives no network call of its own. spring-boot-api's own resource-
    // server config needs the same public/internal split for the same
    // reason - see its JwtDecoderConfig.
    issuer: toPublic(internalIssuer.metadata.issuer),
  });

  return new issuer.Client({
    client_id: process.env.KEYCLOAK_CLIENT_ID,
    client_secret: process.env.KEYCLOAK_CLIENT_SECRET,
    redirect_uris: [`${process.env.BFF_BASE_URL}/auth/callback`],
    response_types: ['code'],
  });
}

async function discoverWithRetry() {
  for (let attempt = 1; attempt <= DISCOVERY_MAX_ATTEMPTS; attempt++) {
    try {
      return await Issuer.discover(process.env.KEYCLOAK_ISSUER);
    } catch (err) {
      if (attempt === DISCOVERY_MAX_ATTEMPTS) {
        throw err;
      }
      console.warn(
        `[oidc] discovery attempt ${attempt}/${DISCOVERY_MAX_ATTEMPTS} failed (${err.message}), ` +
        `retrying in ${DISCOVERY_RETRY_DELAY_MS}ms...`
      );
      await new Promise((resolve) => setTimeout(resolve, DISCOVERY_RETRY_DELAY_MS));
    }
  }
  // Unreachable - the loop above always either returns or throws.
  throw new Error('OIDC discovery exhausted its retries');
}

module.exports = { buildOidcClient };
