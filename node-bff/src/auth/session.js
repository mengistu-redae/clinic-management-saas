'use strict';

const session = require('express-session');
// connect-redis 7 is ESM-first; the CJS build only exposes the store class
// as .default, not as a named export - see its package.json "exports" map.
const RedisStore = require('connect-redis').default;
const { createClient } = require('redis');

/**
 * Sessions live in Redis, not process memory - same instinct as
 * TenantContext being per-request rather than cached: this BFF should be
 * restartable/scalable without silently logging every session out. Redis is
 * already a dependency of the stack for SlotLockService (phase 2), so this
 * doesn't add new infrastructure.
 */
async function buildSessionMiddleware() {
  const redisClient = createClient({ url: process.env.REDIS_URL });
  redisClient.on('error', (err) => console.error('[session] Redis client error:', err));
  await redisClient.connect();

  return session({
    store: new RedisStore({ client: redisClient, prefix: 'clinicops-sess:' }),
    secret: process.env.SESSION_SECRET,
    resave: false,
    saveUninitialized: false,
    cookie: {
      httpOnly: true,
      sameSite: 'lax',
      // The whole stack runs over plain http locally (see docker-compose) -
      // flip this to true once nginx (or whatever's in front) terminates TLS.
      secure: false,
      maxAge: 1000 * 60 * 60 * 8,
    },
  });
}

module.exports = { buildSessionMiddleware };
