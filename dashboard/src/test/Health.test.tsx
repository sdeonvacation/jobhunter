import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import Health from '../pages/Health';
import { fetchApi } from '../api/client';

vi.mock('../api/client', () => ({
  fetchApi: vi.fn(),
}));

const mockHealthReport = (overrides = {}) => ({
  totalEndpoints: 20,
  errored: 1,
  empty: 2,
  neverCrawled: 3,
  errors: [],
  empties: [],
  aggregatorIssues: [],
  ...overrides,
});

describe('Health page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders aggregator issues section when issues exist', async () => {
    vi.mocked(fetchApi).mockResolvedValue(mockHealthReport({
      aggregatorIssues: [
        {
          name: 'stepstone',
          status: 'ERROR',
          jobsFetched: 0,
          errors: 1,
          lastRunAt: '2026-06-07T10:00:00',
          elapsedMs: 1500,
        },
      ],
    }));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('Aggregator Issues (1)')).toBeInTheDocument();
    });
    expect(screen.getByText('stepstone')).toBeInTheDocument();
    expect(screen.getByText('ERROR')).toBeInTheDocument();
  });

  it('renders multiple aggregator issues with correct styling', async () => {
    vi.mocked(fetchApi).mockResolvedValue(mockHealthReport({
      aggregatorIssues: [
        {
          name: 'stepstone',
          status: 'ERROR',
          jobsFetched: 0,
          errors: 1,
          lastRunAt: '2026-06-07T10:00:00',
          elapsedMs: 1500,
        },
        {
          name: 'indeed',
          status: 'EMPTY',
          jobsFetched: 0,
          errors: 0,
          lastRunAt: '2026-06-07T09:00:00',
          elapsedMs: 800,
        },
      ],
    }));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('Aggregator Issues (2)')).toBeInTheDocument();
    });
    expect(screen.getByText('stepstone')).toBeInTheDocument();
    expect(screen.getByText('indeed')).toBeInTheDocument();
  });

  it('does not render aggregator section when no issues', async () => {
    vi.mocked(fetchApi).mockResolvedValue(mockHealthReport({
      aggregatorIssues: [],
    }));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('All endpoints healthy')).toBeInTheDocument();
    });
    expect(screen.queryByText(/Aggregator Issues/)).not.toBeInTheDocument();
  });

  it('shows elapsed time for aggregator issues', async () => {
    vi.mocked(fetchApi).mockResolvedValue(mockHealthReport({
      aggregatorIssues: [
        {
          name: 'linkedin',
          status: 'ERROR',
          jobsFetched: 0,
          errors: 2,
          lastRunAt: '2026-06-07T08:00:00',
          elapsedMs: 3200,
        },
      ],
    }));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('3200ms')).toBeInTheDocument();
    });
  });

  it('shows error count for aggregator with errors', async () => {
    vi.mocked(fetchApi).mockResolvedValue(mockHealthReport({
      aggregatorIssues: [
        {
          name: 'stepstone',
          status: 'ERROR',
          jobsFetched: 0,
          errors: 3,
          lastRunAt: '2026-06-07T10:00:00',
          elapsedMs: 1000,
        },
      ],
    }));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('3 errors')).toBeInTheDocument();
    });
  });

  it('renders stat cards with correct values', async () => {
    vi.mocked(fetchApi).mockResolvedValue(mockHealthReport({
      totalEndpoints: 50,
      errored: 3,
      empty: 5,
      neverCrawled: 2,
    }));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('50')).toBeInTheDocument();
    });
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
  });

  it('shows loading state initially', () => {
    vi.mocked(fetchApi).mockReturnValue(new Promise(() => {}));

    render(<Health />);

    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows error state on fetch failure', async () => {
    vi.mocked(fetchApi).mockRejectedValue(new Error('Network error'));

    render(<Health />);

    await waitFor(() => {
      expect(screen.getByText('Failed to load health report')).toBeInTheDocument();
    });
  });
});
