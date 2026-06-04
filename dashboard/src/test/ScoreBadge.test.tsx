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

  it('renders SVG progress ring', () => {
    const { container } = render(<ScoreBadge score={75} />);
    const svg = container.querySelector('svg');
    expect(svg).toBeInTheDocument();
    const circles = container.querySelectorAll('circle');
    expect(circles.length).toBe(2); // background + progress
  });

  it('progress ring uses correct stroke color for success', () => {
    const { container } = render(<ScoreBadge score={80} />);
    const circles = container.querySelectorAll('circle');
    const progressCircle = circles[1];
    expect(progressCircle.getAttribute('stroke')).toBe('#10b981');
  });

  it('progress ring uses correct stroke color for warning', () => {
    const { container } = render(<ScoreBadge score={50} />);
    const circles = container.querySelectorAll('circle');
    const progressCircle = circles[1];
    expect(progressCircle.getAttribute('stroke')).toBe('#f59e0b');
  });

  it('progress ring uses correct stroke color for danger', () => {
    const { container } = render(<ScoreBadge score={20} />);
    const circles = container.querySelectorAll('circle');
    const progressCircle = circles[1];
    expect(progressCircle.getAttribute('stroke')).toBe('#ef4444');
  });

  it('sm size renders smaller SVG ring', () => {
    const { container } = render(<ScoreBadge score={50} size="sm" />);
    const svg = container.querySelector('svg');
    // radius 6, so svgSize = (6+2)*2 = 16
    expect(svg?.getAttribute('width')).toBe('16');
  });

  it('md size renders larger SVG ring', () => {
    const { container } = render(<ScoreBadge score={50} size="md" />);
    const svg = container.querySelector('svg');
    // radius 8, so svgSize = (8+2)*2 = 20
    expect(svg?.getAttribute('width')).toBe('20');
  });
});
