import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatusPill from './StatusPill.jsx';

describe('StatusPill', () => {
  it('renders the real translated label for a known appointment status', () => {
    render(<StatusPill status="checked_out" />);
    // The real en.json copy, not a mocked one - see test/setup.js.
    expect(screen.getByText('Checked Out')).toBeInTheDocument();
  });

  it('renders the real translated label for a known lab-order status', () => {
    render(<StatusPill status="specimen_collected" />);
    expect(screen.getByText('Specimen Collected')).toBeInTheDocument();
  });

  it('falls back to the raw, underscore-split status for one this app has no translation for', () => {
    render(<StatusPill status="some_unmapped_status" />);
    expect(screen.getByText('some unmapped status')).toBeInTheDocument();
  });

  it('falls back to the same default style for an unmapped status, not throwing', () => {
    render(<StatusPill status="totally_unknown" />);
    const pill = screen.getByText('totally unknown');
    expect(pill.className).toContain('bg-slate-100');
    expect(pill.className).toContain('text-ink-muted');
  });
});
