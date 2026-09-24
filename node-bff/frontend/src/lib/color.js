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
 *
 * `light` (the pill/badge-background tint, e.g. `bg-brand-light text-brand`)
 * is the one variant that actually looks wrong unmodified once the Light/
 * Dark/System theme toggle (frontend phase N) puts it on a dark card - a
 * pale-tint-toward-white badge disappears against a light card in reverse.
 * So when `uiTheme` is `'dark'` it's mixed toward black instead of white
 * (a less extreme amount - 0.65, not 0.92 - since mixing as far toward
 * black as the light-theme tint mixes toward white reads as near-invisible
 * against this app's own dark card background). `dark` (the button-hover
 * darken) is deliberately left theme-independent - a saturated brand color
 * darkened slightly for a hover state reads fine as a button background in
 * either theme, so it wasn't worth a second set of tuning here.
 */
export function deriveShades(channels, uiTheme = 'light') {
  return {
    dark: mix(channels, 'black', 0.28),
    light: uiTheme === 'dark' ? mix(channels, 'black', 0.65) : mix(channels, 'white', 0.92),
  };
}

/**
 * Build the `{ '--brand': ..., '--brand-dark': ..., '--brand-light': ... }`
 * style object for a base hex, or `{}` if the hex is invalid/absent. Pass
 * `prefix` 'brand' or 'accent', and the currently-resolved UI theme
 * ('light'|'dark', see theme/ThemeProvider.jsx) so `light`'s tint direction
 * matches the card it'll actually sit on.
 */
export function themeVars(hex, prefix, uiTheme = 'light') {
  const base = hexToChannels(hex);
  if (!base) return {};
  const { dark, light } = deriveShades(base, uiTheme);
  return {
    [`--${prefix}`]: base,
    [`--${prefix}-dark`]: dark,
    [`--${prefix}-light`]: light,
  };
}
