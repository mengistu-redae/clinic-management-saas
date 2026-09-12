'use strict';

const path = require('path');
const fs = require('fs');
const express = require('express');
const { buildSessionMiddleware } = require('./auth/session');
const { buildOidcClient } = require('./auth/oidc');
const { buildAuthRouter } = require('./routes/auth');
const { buildApiRouter } = require('./routes/api');

// Where the frontend's build output lands - see node-bff/Dockerfile's
// frontend-build stage, which COPYs frontend/dist here as ./public in the
// final image. Not present at all in plain backend-only local dev (nobody's
// run `npm run build` in frontend/) - handled below by falling back to the
// pre-frontend stub rather than erroring.
const FRONTEND_DIST = path.join(__dirname, '..', 'public');
const FRONTEND_INDEX = path.join(FRONTEND_DIST, 'index.html');

async function main() {
  const app = express();
  app.set('trust proxy', 1); // sits behind nginx in docker-compose

  // Built once at startup and closed over by getClient() below - openid-client
  // Clients are stateless and safe to share across requests.
  const client = await buildOidcClient();
  const getClient = () => client;

  app.use(express.json());
  app.use(await buildSessionMiddleware());

  app.get('/health', (req, res) => res.json({ status: 'ok' }));

  app.use('/auth', buildAuthRouter(getClient));
  app.use('/api', buildApiRouter(getClient));

  app.use(express.static(FRONTEND_DIST));

  // SPA fallback: any GET that isn't /health, /auth/*, /api/*, or a real
  // static file (all handled above - express.static already called next()
  // to get here) is a client-side route (e.g. /appointments/abc-123) that
  // only makes sense to the React Router app running in the browser. Hand it
  // index.html and let the router take over. A request actually meant for
  // /api or /auth reaching here is a genuine 404 from those routers, not a
  // SPA route - don't swallow it into the frontend fallback.
  app.get('*', (req, res, next) => {
    if (req.path.startsWith('/api') || req.path.startsWith('/auth')) {
      return next();
    }
    if (fs.existsSync(FRONTEND_INDEX)) {
      return res.sendFile(FRONTEND_INDEX);
    }
    // Frontend hasn't been built (pure backend dev) - same stub as before.
    if (!req.session.user) {
      return res.type('html').send('<a href="/auth/login">Log in</a>');
    }
    res.json({ loggedInAs: req.session.user.preferred_username });
  });

  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    console.error('[bff] unhandled error:', err);
    res.status(500).json({ error: 'Internal BFF error' });
  });

  const port = process.env.PORT || 3000;
  app.listen(port, () => console.log(`node-bff listening on :${port}`));
}

main().catch((err) => {
  console.error('[bff] failed to start:', err);
  process.exit(1);
});
