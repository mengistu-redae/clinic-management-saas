import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Tabs from './Tabs.jsx';

const TAB_DEFS = [
  { key: 'a', label: 'Overview' },
  { key: 'b', label: 'Billing' },
];

describe('Tabs', () => {
  it('renders every tab label', () => {
    render(<Tabs tabs={TAB_DEFS} active="a" onChange={() => {}} />);
    expect(screen.getByText('Overview')).toBeInTheDocument();
    expect(screen.getByText('Billing')).toBeInTheDocument();
  });

  it('marks the active tab as selected and the rest as not', () => {
    render(<Tabs tabs={TAB_DEFS} active="b" onChange={() => {}} />);
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByRole('tab', { name: 'Billing' })).toHaveAttribute('aria-selected', 'true');
  });

  it('calls onChange with the clicked tab key, not the currently active one', async () => {
    const onChange = vi.fn();
    render(<Tabs tabs={TAB_DEFS} active="a" onChange={onChange} />);
    await userEvent.click(screen.getByRole('tab', { name: 'Billing' }));
    expect(onChange).toHaveBeenCalledWith('b');
  });
});
