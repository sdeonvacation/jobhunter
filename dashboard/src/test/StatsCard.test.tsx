import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StatsCard from '../components/StatsCard';

describe('StatsCard', () => {
  it('renders title and value', () => {
    render(<StatsCard title="Total Jobs" value={42} />);
    expect(screen.getByText('Total Jobs')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('renders string value', () => {
    render(<StatsCard title="Status" value="Active" />);
    expect(screen.getByText('Active')).toBeInTheDocument();
  });

  it('renders subtitle when provided', () => {
    render(<StatsCard title="Score" value={75} subtitle="Last 7 days" />);
    expect(screen.getByText('Last 7 days')).toBeInTheDocument();
  });

  it('shows up arrow for trend=up', () => {
    render(<StatsCard title="Growth" value={10} trend="up" />);
    expect(screen.getByText('↑')).toBeInTheDocument();
  });

  it('shows down arrow for trend=down', () => {
    render(<StatsCard title="Decline" value={5} trend="down" />);
    expect(screen.getByText('↓')).toBeInTheDocument();
  });

  it('shows no arrow for neutral trend', () => {
    const { container } = render(<StatsCard title="Stable" value={8} trend="neutral" />);
    expect(container.textContent).not.toContain('↑');
    expect(container.textContent).not.toContain('↓');
  });
});
