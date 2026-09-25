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

/** WCAG relative luminance (0-1) of an "R G B" channel string (each 0-255). */
function relativeLuminance(channels) {
  const [r, g, b] = channels.split(' ').map(Number);
  const linear = (c) => {
    const v = c / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

/** WCAG contrast ratio (>=1) between two "R G B" channel strings. */
function contrastRatio(channelsA, channelsB) {
  const lA = relativeLuminance(channelsA);
  const lB = relativeLuminance(channelsB);
  return (Math.max(lA, lB) + 0.05) / (Math.min(lA, lB) + 0.05);
}

/**
 * This app's own dark card/page background (index.css's --surface dark
 * value, rgb 30 41 59 / #1E293B) - the reference point a dark-mode
 * `text-brand` color is actually read against, whether it's sitting
 * directly on a card/page or on the `-light` badge tint (which, in dark
 * mode, is itself a dark navy/teal close enough in luminance to the plain
 * surface that the same target color works for both - confirmed by
 * computing both, not assumed).
 */
const DARK_SURFACE_CHANNELS = '30 41 59';

/**
 * Lightens `channels` toward white just enough to clear WCAG AA (4.5:1)
 * against this app's own dark surface - real bug found and fixed here:
 * `text-brand` was reusing the plain saturated `--brand` value unchanged
 * in dark mode, which measures ~2.2:1 against both the dark card surface
 * and the dark `-light` badge tint (computed, not eyeballed) - a real
 * contrast failure, not a stylistic nitpick. Steps in small increments
 * rather than solving the exact mix fraction algebraically - easy to
 * verify, and the loop is bounded (fully white always passes, so it
 * always terminates). Applies to *any* base color, not just the platform
 * default, so a clinic's own custom brand/accent color gets the same
 * treatment via themeVars() below instead of silently keeping a
 * potentially-illegible dark-mode text color.
 */
function lightenForDarkText(channels) {
  let mixed = channels;
  for (let amount = 0; amount <= 1; amount += 0.05) {
    mixed = mix(channels, 'white', amount);
    if (contrastRatio(mixed, DARK_SURFACE_CHANNELS) >= 4.5) {
      return mixed;
    }
  }
  return mixed;
}

/**
 * Derive dark/light/text variants from a base channel string, matching
 * roughly how the default brand-dark / brand-light / brand-text relate to
 * brand in index.css.
 *
 * `light` (the pill/badge-background tint, e.g. `bg-brand-light text-brand`)
 * is the variant that looks wrong unmodified once the Light/Dark/System
 * theme toggle (frontend phase N) puts it on a dark card - a pale-tint
 * -toward-white badge disappears against a light card in reverse. So when
 * `uiTheme` is `'dark'` it's mixed toward black instead of white (a less
 * extreme amount - 0.65, not 0.92 - since mixing as far toward black as
 * the light-theme tint mixes toward white reads as near-invisible against
 * this app's own dark card background).
 *
 * `text` (the plain-text/link color, e.g. `text-brand`) is a *different*
 * variant with a different failure mode - real contrast math, not the
 * `light` tint's fixed mix amount, since a fixed amount that's safe for
 * one base hue isn't guaranteed safe for an arbitrary clinic-chosen one.
 * Left unchanged in light mode - `text-brand` already reads fine on a
 * light/white surface for this app's own default and every custom color
 * seen so far, and re-validating a light-mode text color for an
 * arbitrary hex is a separate, not-yet-needed piece of work.
 *
 * `dark` (the button-hover darken) is deliberately left theme
 * -independent - a saturated brand color darkened slightly for a hover
 * state reads fine as a button *background* (with white text on top) in
 * either theme, a different contrast job than plain text again.
 */
export function deriveShades(channels, uiTheme = 'light') {
  return {
    dark: mix(channels, 'black', 0.28),
    light: uiTheme === 'dark' ? mix(channels, 'black', 0.65) : mix(channels, 'white', 0.92),
    text: uiTheme === 'dark' ? lightenForDarkText(channels) : channels,
  };
}

/**
 * Build the `{ '--brand': ..., '--brand-dark': ..., '--brand-light': ...,
 * '--brand-text': ... }` style object for a base hex, or `{}` if the hex
 * is invalid/absent. Pass `prefix` 'brand' or 'accent', and the currently
 * -resolved UI theme ('light'|'dark', see theme/ThemeProvider.jsx) so
 * `light`/`text`'s theme-dependent adjustments match what they'll actually
 * sit on/be read against.
 */
export function themeVars(hex, prefix, uiTheme = 'light') {
  const base = hexToChannels(hex);
  if (!base) return {};
  const { dark, light, text } = deriveShades(base, uiTheme);
  return {
    [`--${prefix}`]: base,
    [`--${prefix}-dark`]: dark,
    [`--${prefix}-light`]: light,
    [`--${prefix}-text`]: text,
  };
}
