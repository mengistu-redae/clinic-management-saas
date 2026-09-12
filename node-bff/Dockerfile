# ---- frontend build stage ----
FROM node:20-alpine AS frontend-build
WORKDIR /app/frontend

# package*.json first so this layer only reruns when dependencies change,
# not on every source edit - same reasoning as the backend stage below.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# ---- backend runtime ----
FROM node:20-alpine

WORKDIR /app

# package*.json first so this layer only reruns when dependencies change,
# not on every source edit. npm ci needs the committed lockfile but gives a
# reproducible install, unlike npm install.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev

COPY src ./src
# Built frontend assets - node-bff/src/index.js serves these via
# express.static and falls back to index.html for client-side SPA routes.
COPY --from=frontend-build /app/frontend/dist ./public

ENV NODE_ENV=production
EXPOSE 3000

CMD ["node", "src/index.js"]
