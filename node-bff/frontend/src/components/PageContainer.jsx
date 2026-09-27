/**
 * Replaces each page's own hand-rolled `mx-auto max-w-* px-0` wrapper (every
 * page under src/pages/ used to declare this independently) with one shared
 * primitive. `width` maps onto this app's existing conventions - `sm`/`md`/
 * `xl` match the max-w-md/max-w-2xl/max-w-xl values already used everywhere;
 * `lg` (max-w-4xl) is new, for the wider DataTable-based list pages; `full`
 * removes the cap entirely (dashboards with multi-column chart grids).
 * Horizontal padding now lives on AppShell/PublicShell's own <main>, not
 * here - this only controls content width and vertical rhythm.
 */
const WIDTHS = {
  sm: 'max-w-md',
  md: 'max-w-2xl',
  xl: 'max-w-xl',
  lg: 'max-w-4xl',
  full: 'max-w-none',
};

export default function PageContainer({ width = 'md', className = '', children }) {
  return <div className={`mx-auto ${WIDTHS[width] || WIDTHS.md} ${className}`}>{children}</div>;
}
