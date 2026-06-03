import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ScoreBadge from '../components/ScoreBadge';

describe('ScoreBadge', () => {
  it('renders score value', () => {
    render(<ScoreBadge score={75} />);
    expect(screen.getByText('75')).toBeInTheDocument();
  });

  it('renders with label', () => {
    render(<ScoreBadge score={60} label="Match" />);
    expect(screen.getByText('Match')).toBeInTheDocument();
    expect(screen.getByText('60')).toBeInTheDocument();
  });

  it('applies green styling for score >= 70', () => {
    const { container } = render(<ScoreBadge score={85} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('bg-green-100');
    expect(badge.className).toContain('text-green-800');
  });

  it('applies yellow styling for score 50-69', () => {
    const { container } = render(<ScoreBadge score={55} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('bg-yellow-100');
    expect(badge.className).toContain('text-yellow-800');
  });

  it('applies red styling for score < 50', () => {
    const { container } = render(<ScoreBadge score={30} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('bg-red-100');
    expect(badge.className).toContain('text-red-800');
  });

  it('uses small size class when size=sm', () => {
    const { container } = render(<ScoreBadge score={50} size="sm" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-xs');
  });

  it('uses medium size class by default', () => {
    const { container } = render(<ScoreBadge score={50} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-sm');
  });
});
