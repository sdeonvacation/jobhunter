import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import EvaluationBadge from '../components/EvaluationBadge';

describe('EvaluationBadge', () => {
  it('renders score value', () => {
    render(<EvaluationBadge score={4} />);
    expect(screen.getByText('4')).toBeInTheDocument();
  });

  it('renders archetype label', () => {
    render(<EvaluationBadge score={3} archetype="Backend Engineer" />);
    expect(screen.getByText('Backend Engineer')).toBeInTheDocument();
  });

  it('applies success styling for score 5', () => {
    const { container } = render(<EvaluationBadge score={5} />);
    const badge = container.querySelector('span');
    expect(badge?.className).toContain('text-success');
    expect(badge?.className).toContain('bg-success/10');
  });

  it('applies blue styling for score 4', () => {
    const { container } = render(<EvaluationBadge score={4} />);
    const badge = container.querySelector('span');
    expect(badge?.className).toContain('text-blue-400');
    expect(badge?.className).toContain('bg-blue-400/10');
  });

  it('applies warning styling for score 3', () => {
    const { container } = render(<EvaluationBadge score={3} />);
    const badge = container.querySelector('span');
    expect(badge?.className).toContain('text-warning');
    expect(badge?.className).toContain('bg-warning/10');
  });

  it('applies orange styling for score 2', () => {
    const { container } = render(<EvaluationBadge score={2} />);
    const badge = container.querySelector('span');
    expect(badge?.className).toContain('text-orange-400');
    expect(badge?.className).toContain('bg-orange-400/10');
  });

  it('applies danger styling for score 1', () => {
    const { container } = render(<EvaluationBadge score={1} />);
    const badge = container.querySelector('span');
    expect(badge?.className).toContain('text-danger');
    expect(badge?.className).toContain('bg-danger/10');
  });

  it('renders legitimacy dot for GREEN tier', () => {
    const { container } = render(<EvaluationBadge score={4} legitimacyTier="GREEN" />);
    const dot = container.querySelector('.bg-success.w-2.h-2');
    expect(dot).toBeInTheDocument();
  });

  it('renders legitimacy dot for AMBER tier', () => {
    const { container } = render(<EvaluationBadge score={3} legitimacyTier="AMBER" />);
    const dot = container.querySelector('.bg-warning.w-2.h-2');
    expect(dot).toBeInTheDocument();
  });

  it('renders legitimacy dot for RED tier', () => {
    const { container } = render(<EvaluationBadge score={1} legitimacyTier="RED" />);
    const dot = container.querySelector('.bg-danger.w-2.h-2');
    expect(dot).toBeInTheDocument();
  });

  it('does not render legitimacy dot when tier not provided', () => {
    const { container } = render(<EvaluationBadge score={4} />);
    const dots = container.querySelectorAll('.w-2.h-2.rounded-full');
    expect(dots.length).toBe(0);
  });

  it('does not render archetype when not provided', () => {
    const { container } = render(<EvaluationBadge score={3} />);
    const archetype = container.querySelector('.uppercase.tracking-wide');
    expect(archetype).not.toBeInTheDocument();
  });
});
