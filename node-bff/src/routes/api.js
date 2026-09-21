'use strict';

const express = require('express');
const { TokenSet } = require('openid-client');

// Paths that are permitAll()'d in spring-boot-api's SecurityConfig and so
// must reach it with no session required here either - mounted ahead of
// requireSession/refreshIfExpired below, so each terminates the router
// before either middleware runs. forwardToApi already only attaches an
// Authorization header when a session exists, so an authenticated
// patient/staff user hitting these same paths still gets their Bearer token
// forwarded exactly as today - this list only ever *adds* anonymous access,
// it doesn't change authenticated behavior.
//
// Literal paths must be listed before any parameterized route on the same
// prefix, since Express's registration-order route matching would
// otherwise let the param route swallow them.
const PUBLIC_ROUTES = [
  ['get', '/clinics'], // patient-portal/guest entry point - see ClinicController.clinics
  ['get', '/clinics/:clinicId/availability'], // see AvailabilityController
  ['get', '/clinics/:clinicId/appointment-types'], // discoverable before booking - see AppointmentTypeController (phase 5)
  ['get', '/clinics/:clinicId/providers'], // discoverable before booking - see ProviderController (booking flow UI phase)
  ['post', '/appointments/guest'], // guest (no-account) booking - see AppointmentController.createGuestAppointment
  ['get', '/appointments/track/:appointmentRef'], // public two-factor tracking lookup
  ['get', '/lab-orders/track/:orderRef'], // public two-factor lab-order tracking - see LabOrderController (phase 7)
];

/**
 * Everything under /api is forwarded to the Spring Boot API with the
 * session's access token attached as a Bearer header - the browser never
 * holds or sends a token itself, only this BFF's session cookie. Spring
 * Security on the other side re-validates that token and re-derives
 * TenantContext from it exactly as if the caller had sent it directly.
 */
function buildApiRouter(getClient) {
  const router = express.Router();

  for (const [method, path] of PUBLIC_ROUTES) {
    router[method](path, forwardToApi);
  }

  router.use(requireSession);
  router.use(refreshIfExpired(getClient));
  router.all('*', forwardToApi);

  return router;
}

function requireSession(req, res, next) {
  if (!req.session.tokenSet) {
    return res.status(401).json({ error: 'Not authenticated' });
  }
  next();
}

/**
 * Refreshes the access token before forwarding if it's expired, so the
 * browser never has to know or care about token lifetimes - that's the
 * point of holding tokens server-side instead of in the browser.
 */
function refreshIfExpired(getClient) {
  return async (req, res, next) => {
    try {
      // req.session.tokenSet round-trips through Redis as plain JSON between
      // requests (connect-redis stores the session with JSON.stringify), so
      // it's a plain object here, not the TokenSet instance auth.js's
      // /callback originally stored - it has the same fields but none of
      // TokenSet's methods. Rewrap it before calling .expired().
      let tokenSet = new TokenSet(req.session.tokenSet);
      if (tokenSet.expired() && tokenSet.refresh_token) {
        tokenSet = await getClient().refresh(tokenSet.refresh_token);
        req.session.tokenSet = tokenSet;
      }
      next();
    } catch (err) {
      // A browser tab left open long enough for Keycloak's own refresh-token
      // lifetime (not just the access token's) to lapse hits this, not the
      // `if` above - the access token looked expired so a refresh was
      // attempted, but the refresh token itself is also no longer valid.
      // openid-client surfaces that as an OPError ("invalid_grant" / "Token
      // is not active") - a genuinely unrecoverable session (the user must
      // log in again) should show up to the frontend as the same 401 shape
      // requireSession already returns for "no session at all", which the
      // frontend already knows how to turn into a login redirect, not an
      // opaque "Internal BFF error". Destroy the now-useless session so it
      // doesn't keep failing the same way on every subsequent request either.
      if (err.name === 'OPError' && err.error === 'invalid_grant') {
        return req.session.destroy(() => {
          res.status(401).json({ error: 'Session expired' });
        });
      }
      next(err);
    }
  };
}

/**
 * express.json() sets req.body to {} even for a request with no body and no
 * Content-Type header at all (e.g. `POST .../check-in` with nothing to
 * send) - checking `body !== undefined` alone doesn't distinguish that from
 * a real JSON body. Forward a body only when one actually has content,
 * always with the right Content-Type when it does - see forwardToApi below.
 */
function shouldForwardBody(method, body) {
  return !['GET', 'HEAD'].includes(method) && body !== undefined && Object.keys(body).length > 0;
}

/** A multipart request (file upload) needs the raw-stream forwarding path in forwardToApi, not the JSON-re-serialize one - see its own comment for why. */
function isMultipartRequest(contentType) {
  return (contentType || '').startsWith('multipart/form-data');
}

async function forwardToApi(req, res, next) {
  try {
    const targetUrl = `${process.env.API_BASE_URL}${req.originalUrl}`;

    const headers = { ...req.headers };
    delete headers.host; // let fetch set the right Host for API_BASE_URL
    delete headers.cookie; // this BFF's session cookie is ours, not the API's
    delete headers['content-length']; // body is about to be re-serialized (or re-streamed) below, length would be stale
    // Only a PUBLIC_ROUTES path (mounted ahead of requireSession above) can
    // reach here with no session at all - spring-boot-api's permitAll() on
    // each of those routes doesn't need a Bearer token either.
    if (req.session?.tokenSet?.access_token) {
      headers.authorization = `Bearer ${req.session.tokenSet.access_token}`;
    }

    // A multipart request (e.g. ProviderController's signature upload,
    // phase 13 - the first file upload anywhere in this app) can't go
    // through the generic JSON-forwarding path below: index.js's global
    // express.json() only parses an application/json body, so req.body
    // stays {} here for any other content type and the actual file bytes
    // would otherwise just be dropped. express.json() also never touches
    // req's own stream for a non-matching content type (body-parser checks
    // the content-type before ever reading anything), so the raw request
    // is still fully intact here - stream it straight through unchanged
    // instead of re-serializing. duplex: 'half' is Node's own requirement
    // for fetch when body is a stream, not something spring-boot-api needs
    // to know about.
    const contentType = req.headers['content-type'] || '';
    if (isMultipartRequest(contentType)) {
      headers['content-type'] = contentType; // keep the boundary parameter fetch needs to parse the parts
      const upstream = await fetch(targetUrl, { method: req.method, headers, body: req, duplex: 'half' });
      return relayUpstreamResponse(upstream, res);
    }

    delete headers['content-type']; // set explicitly below, only when there's an actual JSON body - see shouldForwardBody's comment
    const hasBody = shouldForwardBody(req.method, req.body);
    if (hasBody) {
      headers['content-type'] = 'application/json';
    }

    const upstream = await fetch(targetUrl, {
      method: req.method,
      headers,
      body: hasBody ? JSON.stringify(req.body) : undefined,
    });
    return relayUpstreamResponse(upstream, res);
  } catch (err) {
    next(err);
  }
}

async function relayUpstreamResponse(upstream, res) {
  res.status(upstream.status);
  upstream.headers.forEach((value, key) => {
    if (key.toLowerCase() !== 'transfer-encoding') {
      res.setHeader(key, value);
    }
  });
  res.send(Buffer.from(await upstream.arrayBuffer()));
}

module.exports = { buildApiRouter, requireSession, refreshIfExpired, shouldForwardBody, isMultipartRequest };
