/**
 * Colour helpers for per-clinic theming. The Tailwind `brand`/`accent`
 * tokens are `rgb(var(--brand) / <alpha-value>)`, so the CSS vars must hold
 * space-separated RGB *channels* ("29 78 216"), not hex. Ported near
 * -verbatim from the reference bus-ticketing-saas project's own
 * `lib/color.js` - generic colour math, no domain-specific logic.
 */

/** '#1D4ED8' -> '29 78 216', or null if not a 6-digit hex. */
export function hexToChannels(hex) {
  if (typeof hex !== 'string') return null;
  const m = /^#?([0-9a-fA-F]{6})$/.exec(hex.trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return `${(n >> 16) & 255} ${(n >> 8) & 255} ${n & 255}`;
}

function mix(channels, target, amount) {
  const [r, g, b] = channels.split(' ').map(Number);
  const t = target === 'white' ? 255 : 0;
  const f = (c) => Math.round(c + (t - c) * amount);
  return `${f(r)} ${f(g)} ${f(b)}`;
}

/**
 * Derive dark/light variants from a base channel string, matching roughly
 * how the default brand-dark / brand-light relate to brand in index.css.
 */
export function deriveShades(channels) {
  return {
    dark: mix(channels, 'black', 0.28),
    light: mix(channels, 'white', 0.92),
  };
}

/**
 * Build the `{ '--brand': ..., '--brand-dark': ..., '--brand-light': ... }`
 * style object for a base hex, or `{}` if the hex is invalid/absent. Pass
 * `prefix` 'brand' or 'accent'.
 */
export function themeVars(hex, prefix) {
  const base = hexToChannels(hex);
  if (!base) return {};
  const { dark, light } = deriveShades(base);
  return {
    [`--${prefix}`]: base,
    [`--${prefix}-dark`]: dark,
    [`--${prefix}-light`]: light,
  };
}
