import { useTheme } from '../../theme/ThemeProvider.jsx';

/**
 * Chart colors, deliberately NOT read from this app's own --brand/--accent/
 * --danger CSS variables the way the rest of the UI is themed - those
 * tokens are tuned for large filled surfaces (buttons, badges) with white
 * text on top, a different contrast job than a thin chart line/bar sitting
 * directly on the page background. Verified live with the dataviz skill's
 * validator (node scripts/validate_palette.js) before picking these:
 * this app's actual dark-mode --brand (unchanged from light, #1D4ED8) comes
 * out under 3:1 against the dark chart surface, and the light-mode
 * --success/--danger pair fails CVD separation (deutan ΔE 5.0, red/green).
 * This palette is a small, separately-validated set instead - one hue per
 * theme for "primary" (activity/volume), one shared teal for revenue
 * (passes the lightness/chroma/contrast bands in both themes unchanged),
 * and a primary+danger pair for the one chart that puts two colors on
 * screen at once (status outcome breakdown) - that pair passes CVD
 * separation clearly (ΔE ~26-28) in both themes, unlike primary+success.
 * Deliberately not tied to a clinic's own custom brand color either - a
 * per-tenant hex can't be re-validated at runtime, so charts stay on this
 * fixed, checked palette regardless of branding.
 */
const CHART_PALETTE = {
  light: { primary: '#1D4ED8', teal: '#0D9488', danger: '#DC2626', grid: '#E2E8F0', axis: '#64748B', ink: '#0F172A', surface: '#FFFFFF' },
  dark: { primary: '#3987E5', teal: '#0D9488', danger: '#E66767', grid: '#475569', axis: '#94A3B8', ink: '#F1F5F9', surface: '#1E293B' },
};

export function useChartPalette() {
  const { resolvedTheme } = useTheme();
  return CHART_PALETTE[resolvedTheme] || CHART_PALETTE.light;
}
