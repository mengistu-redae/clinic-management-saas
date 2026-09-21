'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { requireSession, refreshIfExpired, shouldForwardBody, isMultipartRequest } = require('../src/routes/api');

function mockReqRes(session) {
  session.destroy = session.destroy || ((cb) => cb());
  const req = { session };
  const res = {
    statusCode: null,
    body: null,
    status(code) {
      this.statusCode = code;
      return this;
    },
    json(body) {
      this.body = body;
      return this;
    },
  };
  return { req, res };
}

test('requireSession', async (t) => {
  await t.test('rejects with 401 when there is no session tokenSet', () => {
    const { req, res } = mockReqRes({});
    let nextCalled = false;

    requireSession(req, res, () => {
      nextCalled = true;
    });

    assert.equal(res.statusCode, 401);
    assert.equal(nextCalled, false);
  });

  await t.test('calls next() when a tokenSet is present', () => {
    const { req, res } = mockReqRes({ tokenSet: { access_token: 'x' } });
    let nextCalled = false;

    requireSession(req, res, () => {
      nextCalled = true;
    });

    assert.equal(nextCalled, true);
    assert.equal(res.statusCode, null);
  });
});

test('refreshIfExpired', async (t) => {
  // Regression test for the exact bug this pattern guards against:
  // "TypeError: tokenSet.expired is not a function". req.session.tokenSet
  // round-trips through Redis as plain JSON (connect-redis serializes the
  // session with JSON.stringify), so on every request after the first it's
  // a plain object - not the real TokenSet instance auth.js's /callback
  // originally stored - and has none of TokenSet's methods.
  await t.test('does not throw on a plain-object tokenSet from a Redis-loaded session', async () => {
    const plainTokenSet = {
      access_token: 'abc',
      refresh_token: 'refresh-abc',
      expires_at: Math.floor(Date.now() / 1000) + 3600, // not expired
    };
    const { req, res } = mockReqRes({ tokenSet: plainTokenSet });
    const getClient = () => ({
      refresh: async () => {
        throw new Error('should not be called - token is not expired');
      },
    });
    let nextErr;

    await refreshIfExpired(getClient)(req, res, (err) => {
      nextErr = err;
    });

    assert.equal(nextErr, undefined);
  });

  await t.test('leaves a non-expired tokenSet untouched', async () => {
    const plainTokenSet = {
      access_token: 'abc',
      refresh_token: 'refresh-abc',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
    };
    const { req, res } = mockReqRes({ tokenSet: plainTokenSet });
    const getClient = () => ({ refresh: async () => { throw new Error('unexpected refresh call'); } });

    await refreshIfExpired(getClient)(req, res, () => {});

    assert.equal(req.session.tokenSet, plainTokenSet);
  });

  await t.test('refreshes and replaces an expired plain-object tokenSet', async () => {
    const expiredTokenSet = {
      access_token: 'old',
      refresh_token: 'refresh-abc',
      expires_at: Math.floor(Date.now() / 1000) - 10, // already expired
    };
    const refreshedTokenSet = {
      access_token: 'new',
      refresh_token: 'refresh-abc',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
    };
    const { req, res } = mockReqRes({ tokenSet: expiredTokenSet });
    let refreshedWithToken;
    const getClient = () => ({
      refresh: async (refreshToken) => {
        refreshedWithToken = refreshToken;
        return refreshedTokenSet;
      },
    });
    let nextCalled = false;

    await refreshIfExpired(getClient)(req, res, () => {
      nextCalled = true;
    });

    assert.equal(refreshedWithToken, 'refresh-abc');
    assert.equal(req.session.tokenSet, refreshedTokenSet);
    assert.equal(nextCalled, true);
  });

  await t.test('does not refresh an expired tokenSet with no refresh_token', async () => {
    const expiredTokenSet = {
      access_token: 'old',
      expires_at: Math.floor(Date.now() / 1000) - 10,
    };
    const { req, res } = mockReqRes({ tokenSet: expiredTokenSet });
    const getClient = () => ({ refresh: async () => { throw new Error('should not be called'); } });

    await refreshIfExpired(getClient)(req, res, () => {});

    assert.equal(req.session.tokenSet, expiredTokenSet);
  });

  await t.test('passes a refresh failure to next(err) rather than throwing', async () => {
    const expiredTokenSet = {
      access_token: 'old',
      refresh_token: 'refresh-abc',
      expires_at: Math.floor(Date.now() / 1000) - 10,
    };
    const { req, res } = mockReqRes({ tokenSet: expiredTokenSet });
    const refreshError = new Error('Keycloak refresh_token expired');
    const getClient = () => ({ refresh: async () => { throw refreshError; } });
    let nextErr;

    await refreshIfExpired(getClient)(req, res, (err) => {
      nextErr = err;
    });

    assert.equal(nextErr, refreshError);
  });

  // Regression test for the exact failure mode this branch exists for: a
  // browser tab left open long enough that Keycloak's refresh token itself
  // (not just the access token) had lapsed. openid-client surfaces that as
  // an OPError with error: 'invalid_grant' - without special-casing it,
  // this falls through to next(err) and the generic 500 handler, showing
  // the frontend an opaque "Internal BFF error" instead of the 401 it
  // already knows how to turn into a login redirect.
  await t.test('treats an invalid_grant refresh failure as an expired session, not a 500', async () => {
    const expiredTokenSet = {
      access_token: 'old',
      refresh_token: 'refresh-abc',
      expires_at: Math.floor(Date.now() / 1000) - 10,
    };
    const { req, res } = mockReqRes({ tokenSet: expiredTokenSet });
    let destroyed = false;
    req.session.destroy = (cb) => {
      destroyed = true;
      cb();
    };
    const opError = Object.assign(new Error('invalid_grant (Token is not active)'), {
      name: 'OPError',
      error: 'invalid_grant',
      error_description: 'Token is not active',
    });
    const getClient = () => ({ refresh: async () => { throw opError; } });
    let nextCalled = false;

    await refreshIfExpired(getClient)(req, res, () => {
      nextCalled = true;
    });

    assert.equal(nextCalled, false);
    assert.equal(destroyed, true);
    assert.equal(res.statusCode, 401);
    assert.equal(res.body.error, 'Session expired');
  });
});

test('shouldForwardBody', async (t) => {
  // Regression test for a real bug class: express.json() sets req.body to
  // {} for a request with no body at all (e.g. a front-desk check-in call
  // with no {reason: ...}), not undefined - forwardToApi treating that as
  // "has a body" sends Node fetch()'s default "text/plain;charset=UTF-8"
  // Content-Type for it (since nothing overrode it), which spring-boot-api's
  // @RequestBody(required = false) endpoints can turn into a 403
  // "insufficient_scope" instead of the plain success a genuinely empty
  // body should get.
  await t.test('is false for an empty-object body (no body was actually sent)', () => {
    assert.equal(shouldForwardBody('POST', {}), false);
  });

  await t.test('is true for a body with real content', () => {
    assert.equal(shouldForwardBody('POST', { reason: 'change of plans' }), true);
  });

  await t.test('is false when body is undefined', () => {
    assert.equal(shouldForwardBody('POST', undefined), false);
  });

  await t.test('is false for GET/HEAD even with a non-empty body object', () => {
    assert.equal(shouldForwardBody('GET', { foo: 'bar' }), false);
    assert.equal(shouldForwardBody('HEAD', { foo: 'bar' }), false);
  });

  await t.test('is true for DELETE with real content', () => {
    assert.equal(shouldForwardBody('DELETE', { reason: 'x' }), true);
  });
});

test('isMultipartRequest', async (t) => {
  // Regression test for a real bug: forwardToApi used to always
  // JSON-serialize req.body, but express.json() (index.js's global
  // middleware) never populates req.body for a multipart request - it
  // stayed {} and the actual uploaded file bytes were silently dropped
  // before ever reaching spring-boot-api. First hit by ProviderController's
  // signature-upload endpoint (phase 13), the first file upload anywhere
  // in this app. isMultipartRequest is what forwardToApi now checks to
  // decide whether to stream the raw request through instead.
  await t.test('is true for a real multipart content-type with a boundary', () => {
    assert.equal(isMultipartRequest('multipart/form-data; boundary=----WebKitFormBoundaryabc123'), true);
  });

  await t.test('is false for a JSON content-type', () => {
    assert.equal(isMultipartRequest('application/json'), false);
  });

  await t.test('is false for no content-type at all', () => {
    assert.equal(isMultipartRequest(undefined), false);
    assert.equal(isMultipartRequest(''), false);
  });
});
