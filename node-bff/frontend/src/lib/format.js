const dateTimeFormatter = new Intl.DateTimeFormat(undefined, {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

const timeFormatter = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });

const dayLabelFormatter = new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' });

export function formatDateTime(iso) {
  if (!iso) return '—';
  return dateTimeFormatter.format(new Date(iso));
}

export function formatTime(iso) {
  if (!iso) return '—';
  return timeFormatter.format(new Date(iso));
}

export function formatCurrency(amount) {
  if (amount === null || amount === undefined) return '—';
  return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount);
}

/** "Tue, Sep 2" - short day label, used to group open slots by day. */
export function formatDayLabel(iso) {
  return dayLabelFormatter.format(new Date(iso));
}
