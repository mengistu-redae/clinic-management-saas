/**
 * The `rounded-xl border border-slate-200 bg-surface p-4` wrapper repeated
 * across every list-row/panel since phase A, as one component. `hover` adds
 * the existing clickable-row shadow transition (front-desk/Appointments.jsx
 * etc. already used this exact treatment inline).
 */
export default function Card({ as: Component = 'div', hover = false, className = '', children, ...props }) {
  return (
    <Component
      className={`rounded-xl border border-slate-200 bg-surface p-4 ${hover ? 'shadow-sm transition-shadow hover:shadow-md' : ''} ${className}`}
      {...props}
    >
      {children}
    </Component>
  );
}
