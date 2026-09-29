import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import VisitSummaryLink from './VisitSummaryLink.jsx';

describe('VisitSummaryLink', () => {
  it('renders a same-origin download link with the given href and the real translated label', () => {
    render(<VisitSummaryLink href="/api/appointments/abc-123/visit-summary/pdf" />);
    // The real en.json copy, not a mocked one - see test/setup.js.
    const link = screen.getByText('Download visit summary');
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '/api/appointments/abc-123/visit-summary/pdf');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('renders the patient-facing my-appointments href just as plainly', () => {
    render(<VisitSummaryLink href="/api/my-appointments/xyz-789/visit-summary/pdf" />);
    expect(screen.getByText('Download visit summary')).toHaveAttribute('href', '/api/my-appointments/xyz-789/visit-summary/pdf');
  });
});
