import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import LivenessBadge from '../components/LivenessBadge';

describe('LivenessBadge', () => {
  it('renders ACTIVE status text', () => {
    render(<LivenessBadge status="ACTIVE" />);
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
  });

  it('renders EXPIRED status text', () => {
    render(<LivenessBadge status="EXPIRED" />);
    expect(screen.getByText('EXPIRED')).toBeInTheDocument();
  });

  it('renders UNCERTAIN status text', () => {
    render(<LivenessBadge status="UNCERTAIN" />);
    expect(screen.getByText('UNCERTAIN')).toBeInTheDocument();
  });

  it('applies success styling for ACTIVE', () => {
    const { container } = render(<LivenessBadge status="ACTIVE" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-success');
    expect(badge.className).toContain('bg-success/10');
  });

  it('applies danger styling for EXPIRED', () => {
    const { container } = render(<LivenessBadge status="EXPIRED" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-danger');
    expect(badge.className).toContain('bg-danger/10');
  });

  it('applies warning styling for UNCERTAIN', () => {
    const { container } = render(<LivenessBadge status="UNCERTAIN" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('text-warning');
    expect(badge.className).toContain('bg-warning/10');
  });

  it('renders green dot for ACTIVE', () => {
    const { container } = render(<LivenessBadge status="ACTIVE" />);
    const dot = container.querySelector('.bg-success.rounded-full');
    expect(dot).toBeInTheDocument();
  });

  it('renders red dot for EXPIRED', () => {
    const { container } = render(<LivenessBadge status="EXPIRED" />);
    const dot = container.querySelector('.bg-danger.rounded-full');
    expect(dot).toBeInTheDocument();
  });

  it('renders amber dot for UNCERTAIN', () => {
    const { container } = render(<LivenessBadge status="UNCERTAIN" />);
    const dot = container.querySelector('.bg-warning.rounded-full');
    expect(dot).toBeInTheDocument();
  });

  it('shows title with checked date when provided', () => {
    const { container } = render(<LivenessBadge status="ACTIVE" checkedAt="2026-06-15T10:00:00" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.getAttribute('title')).toContain('Checked:');
  });

  it('does not show title when checkedAt not provided', () => {
    const { container } = render(<LivenessBadge status="ACTIVE" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.getAttribute('title')).toBeNull();
  });

  it('has badge styling classes', () => {
    const { container } = render(<LivenessBadge status="ACTIVE" />);
    const badge = container.firstChild as HTMLElement;
    expect(badge.className).toContain('inline-flex');
    expect(badge.className).toContain('items-center');
    expect(badge.className).toContain('rounded-full');
    expect(badge.className).toContain('text-xs');
  });
});
