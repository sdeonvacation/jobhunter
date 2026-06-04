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

  it('applies success styling for score >= 70', () => {
    const { container } = render(<ScoreBadge score={85} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-success');
    expect(badge.className).toContain('bg-success/10');
  });

  it('applies warning styling for score 40-69', () => {
    const { container } = render(<ScoreBadge score={55} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-warning');
    expect(badge.className).toContain('bg-warning/10');
  });

  it('applies danger styling for score < 40', () => {
    const { container } = render(<ScoreBadge score={30} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-danger');
    expect(badge.className).toContain('bg-danger/10');
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

  it('applies font-mono for numeric display', () => {
    const { container } = render(<ScoreBadge score={92} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('font-mono');
  });

  it('boundary: score 70 gets success styling', () => {
    const { container } = render(<ScoreBadge score={70} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-success');
  });

  it('boundary: score 40 gets warning styling', () => {
    const { container } = render(<ScoreBadge score={40} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-warning');
  });

  it('boundary: score 39 gets danger styling', () => {
    const { container } = render(<ScoreBadge score={39} />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-danger');
  });
});
