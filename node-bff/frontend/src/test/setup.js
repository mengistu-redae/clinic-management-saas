import '@testing-library/jest-dom/vitest';
// Same import main.jsx uses to register the real i18next instance globally
// - every test gets a working useTranslation() against the app's real
// en/am locale files for free, no per-test mocking needed.
import '../i18n/index.js';
