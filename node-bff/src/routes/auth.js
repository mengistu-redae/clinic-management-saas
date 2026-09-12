'use strict';

const express = require('express');
const { generators } = require('openid-client');

/**
 * Decodes a JWT's payload without verifying its signature - safe here only
 * because the token's authenticity was already established by the OAuth
 * token exchange with Keycloak (client.callback() above), this just reads a
 * claim back out of a token the BFF already trusts.
 *
 * Needed because tokenSet.claims() (used below) only exposes the ID token's
 * claims - the reference bus-ticketing-saas project confirmed live against
 * its own realm that the ID token carries no realm_access.roles claim at
 * all, only the access token has it. Re-verify this against a real decoded
 * token once this realm exists (see infra/keycloak/realm-export.json) rather
 * than assuming it carries over unchanged. The frontend needs role info for
 * UX-only nav/route gating (see RequireRole.jsx), hence merging it into
 * req.session.user below rather than leaving it inaccessible to /me.
 */
function decodeJwtPayload(token) {
  try {
    const payload = token.split('.')[1];
    return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
}

/**
 * Authorization Code + PKCE against Keycloak. The BFF is the only thing that
 * ever sees tokens - the browser only ever gets a session cookie. See
 * routes/api.js for how those tokens get used afterwards.
 */
function buildAuthRouter(getClient) {
  const router = express.Router();

  router.get('/login', (req, res, next) => {
    try {
      const client = getClient();
      const state = generators.state();
      const nonce = generators.nonce();
      const codeVerifier = generators.codeVerifier();
      const codeChallenge = generators.codeChallenge(codeVerifier);

      // Stashed in the session (not a separate cookie) so it survives
      // exactly as long as this one login attempt needs it to.
      req.session.oidc = { state, nonce, codeVerifier };

      const authorizationUrl = client.authorizationUrl({
        // "organization" is an optional client scope in Keycloak (26.x auto-
        // creates it once Organizations is enabled on the realm, but doesn't
        // default it onto the client) - without requesting it explicitly,
        // staff tokens carry no org claim at all and TenantContextFilter
        // resolves TenantContext to null for every staff request.
        scope: 'openid profile email organization',
        state,
        nonce,
        code_challenge: codeChallenge,
        code_challenge_method: 'S256',
      });
      res.redirect(authorizationUrl);
    } catch (err) {
      next(err);
    }
  });

  router.get('/callback', async (req, res, next) => {
    try {
      const client = getClient();
      const params = client.callbackParams(req);
      const stashed = req.session.oidc;

      if (!stashed || params.state !== stashed.state) {
        return res.status(400).send('Invalid or expired login attempt - please try logging in again.');
      }

      const tokenSet = await client.callback(
        `${process.env.BFF_BASE_URL}/auth/callback`,
        params,
        { state: stashed.state, nonce: stashed.nonce, code_verifier: stashed.codeVerifier }
      );

      delete req.session.oidc;
      // Holds access_token, refresh_token, id_token and expiry - see
      // routes/api.js for the refresh-before-forwarding logic that reads it.
      req.session.tokenSet = tokenSet;

      const userClaims = tokenSet.claims();
      const accessTokenClaims = decodeJwtPayload(tokenSet.access_token);
      if (accessTokenClaims?.realm_access) {
        userClaims.realm_access = accessTokenClaims.realm_access;
      }
      req.session.user = userClaims;

      res.redirect('/');
    } catch (err) {
      next(err);
    }
  });

  router.post('/logout', (req, res, next) => {
    try {
      const client = getClient();
      const idToken = req.session.tokenSet && req.session.tokenSet.id_token;
      req.session.destroy((err) => {
        if (err) {
          return next(err);
        }
        res.redirect(client.endSessionUrl({
          id_token_hint: idToken,
          post_logout_redirect_uri: process.env.BFF_BASE_URL,
        }));
      });
    } catch (err) {
      next(err);
    }
  });

  router.get('/me', (req, res) => {
    if (!req.session.user) {
      return res.status(401).json({ authenticated: false });
    }
    res.json({ authenticated: true, user: req.session.user });
  });

  return router;
}

module.exports = { buildAuthRouter, decodeJwtPayload };
