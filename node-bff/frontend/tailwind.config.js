/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        // brand + accent are runtime-themeable per clinic (phase 5,
        // branding): the values are CSS custom properties (space-separated
        // RGB channels so Tailwind's <alpha-value> still works, e.g.
        // ring-brand/20). Defaults live in src/index.css :root; a future
        // BrandingProvider overrides them on document.documentElement for a
        // signed-in clinic's staff.
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
        success: { DEFAULT: '#16A34A', light: '#E9F9EF' },
        danger: { DEFAULT: '#DC2626', light: '#FDECEC' },
        warning: { DEFAULT: '#D97706', light: '#FEF3E2' },
        surface: '#FFFFFF',
        ink: '#0F172A',
        'ink-muted': '#64748B',
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
