'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { decodeJwtPayload } = require('../src/routes/auth');

function fakeJwt(payload) {
  const header = Buffer.from(JSON.stringify({ alg: 'none' })).toString('base64url');
  const body = Buffer.from(JSON.stringify(payload)).toString('base64url');
  return `${header}.${body}.`;
}

test('decodeJwtPayload', async (t) => {
  // Regression test for the exact gap this function exists to cover:
  // tokenSet.claims() (the ID token) may carry no realm_access.roles on a
  // real token from this realm - only the access token does - so /callback
  // reads it out of the access token via this function instead. Re-verify
  // against a real decoded token once this realm actually exists.
  await t.test('reads realm_access.roles out of a real-shaped access token payload', () => {
    const token = fakeJwt({ sub: 'abc', realm_access: { roles: ['patient'] } });
    assert.deepEqual(decodeJwtPayload(token), { sub: 'abc', realm_access: { roles: ['patient'] } });
  });

  await t.test('returns null rather than throwing on a malformed token', () => {
    assert.equal(decodeJwtPayload('not-a-jwt'), null);
  });

  await t.test('returns null rather than throwing on an empty string', () => {
    assert.equal(decodeJwtPayload(''), null);
  });
});
