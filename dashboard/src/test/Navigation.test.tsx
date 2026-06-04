import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import Navigation from '../components/Navigation';

function renderWithRouter() {
  return render(
    <BrowserRouter>
      <Navigation />
    </BrowserRouter>,
  );
}

describe('Navigation', () => {
  it('renders app title', () => {
    renderWithRouter();
    expect(screen.getByText('JobHub')).toBeInTheDocument();
  });

  it('renders all navigation links', () => {
    renderWithRouter();
    expect(screen.getByText('Jobs')).toBeInTheDocument();
    expect(screen.getByText('Pipeline')).toBeInTheDocument();
    expect(screen.getByText('Companies')).toBeInTheDocument();
    expect(screen.getByText('Discovery')).toBeInTheDocument();
    expect(screen.getByText('Digest')).toBeInTheDocument();
  });

  it('links have correct hrefs', () => {
    renderWithRouter();
    expect(screen.getByText('Jobs').closest('a')).toHaveAttribute('href', '/jobs');
    expect(screen.getByText('Pipeline').closest('a')).toHaveAttribute('href', '/pipeline');
    expect(screen.getByText('Companies').closest('a')).toHaveAttribute('href', '/companies');
    expect(screen.getByText('Discovery').closest('a')).toHaveAttribute('href', '/discovery');
    expect(screen.getByText('Digest').closest('a')).toHaveAttribute('href', '/digest');
  });

  it('renders SVG icons (not emoji)', () => {
    const { container } = renderWithRouter();
    const svgs = container.querySelectorAll('svg');
    expect(svgs.length).toBe(5);
  });

  it('renders accent gradient underline', () => {
    const { container } = renderWithRouter();
    const gradientEl = container.querySelector('[class*="bg-gradient-to-r"]');
    expect(gradientEl).toBeInTheDocument();
  });

  it('renders footer text', () => {
    renderWithRouter();
    expect(screen.getByText('Command Center')).toBeInTheDocument();
  });
});
