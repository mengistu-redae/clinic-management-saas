/**
 * Encodes the button classNames this app has repeated ad hoc since phase A
 * (rounded-lg bg-brand px-4 py-2 text-sm font-semibold text-white
 * hover:bg-brand-dark, etc.) as one component - not a retrofit of every
 * existing button, just the shape new/touched pieces (Sidebar, DataTable,
 * PageHeader actions, and any form this redesign otherwise edits) reach for
 * from now on.
 */
const VARIANTS = {
  primary: 'bg-brand text-white hover:bg-brand-dark focus-visible:ring-brand/30',
  accent: 'bg-accent text-white hover:bg-accent-dark focus-visible:ring-accent/30',
  secondary:
    'border border-slate-300 bg-surface text-ink hover:bg-slate-100 focus-visible:ring-brand/20',
  ghost: 'text-ink-muted hover:bg-slate-100 hover:text-ink focus-visible:ring-brand/20',
  danger: 'bg-danger text-white hover:opacity-90 focus-visible:ring-danger/30',
};

const SIZES = {
  sm: 'px-2.5 py-1.5 text-xs',
  md: 'px-4 py-2 text-sm',
};

export default function Button({
  as: Component = 'button',
  variant = 'primary',
  size = 'md',
  className = '',
  type,
  ...props
}) {
  const resolvedType = Component === 'button' ? type || 'button' : undefined;
  return (
    <Component
      type={resolvedType}
      className={`inline-flex items-center justify-center gap-1.5 rounded-lg font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant] || VARIANTS.primary} ${SIZES[size] || SIZES.md} ${className}`}
      {...props}
    />
  );
}
