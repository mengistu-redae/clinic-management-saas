/** @type {import('tailwindcss').Config} */
export default {
  // Class-based, not 'media' - a manual Light/Dark/System toggle (see
  // theme/ThemeProvider.jsx) needs to force a specific theme regardless of
  // the OS setting; 'system' is resolved in JS (matchMedia) and applied as
  // this same .dark class, so there's only ever one source of truth for
  // which theme is active.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // brand + accent are runtime-themeable per clinic (phase 5,
        // branding): the values are CSS custom properties (space-separated
        // RGB channels so Tailwind's <alpha-value> still works, e.g.
        // ring-brand/20). Defaults live in src/index.css :root (light) and
        // :root.dark (dark); BrandingProvider overrides them on
        // document.documentElement for a signed-in clinic's staff, in
        // both themes (see lib/color.js's theme-aware deriveShades).
        brand: {
          DEFAULT: 'rgb(var(--brand) / <alpha-value>)',
          dark: 'rgb(var(--brand-dark) / <alpha-value>)',
          light: 'rgb(var(--brand-light) / <alpha-value>)',
        },
        accent: {
          DEFAULT: 'rgb(var(--accent) / <alpha-value>)',
          dark: 'rgb(var(--accent-dark) / <alpha-value>)',
          light: 'rgb(var(--accent-light) / <alpha-value>)',
        },
        // success/danger/warning/surface/ink - same CSS-var pattern as
        // brand/accent, added for the theme toggle (frontend phase N):
        // each has a light-theme and dark-theme value in index.css, so
        // every existing bg-danger-light/text-ink/etc. usage across the
        // app switches automatically with no per-page changes.
        success: {
          DEFAULT: 'rgb(var(--success) / <alpha-value>)',
          light: 'rgb(var(--success-light) / <alpha-value>)',
        },
        danger: {
          DEFAULT: 'rgb(var(--danger) / <alpha-value>)',
          light: 'rgb(var(--danger-light) / <alpha-value>)',
        },
        warning: {
          DEFAULT: 'rgb(var(--warning) / <alpha-value>)',
          light: 'rgb(var(--warning-light) / <alpha-value>)',
        },
        surface: 'rgb(var(--surface) / <alpha-value>)',
        ink: 'rgb(var(--ink) / <alpha-value>)',
        'ink-muted': 'rgb(var(--ink-muted) / <alpha-value>)',
        // Only the four slate shades this app actually uses (confirmed by
        // grep across src/ before adding this) are overridden - Tailwind
        // deep-merges nested color objects under `extend`, so slate-400
        // through slate-900 keep their normal static Tailwind values,
        // untouched and unaffected by theme. white/black are deliberately
        // NOT overridden - their few uses (logo/signature image preview
        // backdrops, white text on a solid-colored button) are meant to
        // stay literal in both themes, not flip.
        slate: {
          50: 'rgb(var(--slate-50) / <alpha-value>)',
          100: 'rgb(var(--slate-100) / <alpha-value>)',
          200: 'rgb(var(--slate-200) / <alpha-value>)',
          300: 'rgb(var(--slate-300) / <alpha-value>)',
        },
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
