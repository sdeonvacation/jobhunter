import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { BrowserRouter } from 'react-router-dom';
import Navigation from '../components/Navigation';

// Mock fetchApi to avoid real network calls
vi.mock('../api/client', () => ({
  fetchApi: vi.fn().mockResolvedValue({ totalEndpoints: 42 }),
}));

function renderWithRouter() {
  return render(
    <BrowserRouter>
      <Navigation />
    </BrowserRouter>,
  );
}

describe('Navigation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders app title', () => {
    renderWithRouter();
    expect(screen.getByText('JobHub')).toBeInTheDocument();
  });

  it('renders all navigation links', () => {
    renderWithRouter();
    expect(screen.getByText('Jobs')).toBeInTheDocument();
    expect(screen.getByText('Applied')).toBeInTheDocument();
    expect(screen.getByText('Companies')).toBeInTheDocument();
    expect(screen.getByText('Daily Digest')).toBeInTheDocument();
    expect(screen.getByText('Health')).toBeInTheDocument();
  });

  it('links have correct hrefs', () => {
    renderWithRouter();
    expect(screen.getByText('Jobs').closest('a')).toHaveAttribute('href', '/jobs');
    expect(screen.getByText('Applied').closest('a')).toHaveAttribute('href', '/applied');
    expect(screen.getByText('Companies').closest('a')).toHaveAttribute('href', '/companies');
    expect(screen.getByText('Daily Digest').closest('a')).toHaveAttribute('href', '/digest');
    expect(screen.getByText('Health').closest('a')).toHaveAttribute('href', '/health');
  });

  it('renders SVG icons', () => {
    const { container } = renderWithRouter();
    const svgs = container.querySelectorAll('svg');
    expect(svgs.length).toBe(5);
  });

  it('renders accent gradient underline', () => {
    const { container } = renderWithRouter();
    const gradientEl = container.querySelector('[class*="bg-gradient-to-r"]');
    expect(gradientEl).toBeInTheDocument();
  });

  it('fetches and displays endpoint count', async () => {
    renderWithRouter();
    await waitFor(() => {
      expect(screen.getByText('42 endpoints tracked')).toBeInTheDocument();
    });
  });

  it('shows placeholder while loading', () => {
    renderWithRouter();
    expect(screen.getByText('...')).toBeInTheDocument();
  });

  it('nav items have hover scale class on icon wrapper', () => {
    const { container } = renderWithRouter();
    const iconWrappers = container.querySelectorAll('[class*="group-hover:scale"]');
    expect(iconWrappers.length).toBe(5);
  });
});
