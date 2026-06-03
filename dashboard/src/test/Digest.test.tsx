import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import Digest from '../pages/Digest';
import { api, ApiError } from '../api/client';

vi.mock('../api/client', () => ({
  ApiError: class ApiError extends Error {
    status: number;
    constructor(status: number, message: string) {
      super(message);
      this.status = status;
      this.name = 'ApiError';
    }
  },
  api: {
    jobs: {
      getDailyDigest: vi.fn(),
    },
  },
}));

const mockDigest = {
  date: '2026-06-03',
  newJobsCount: 12,
  skippedCount: 3,
  topOpportunityTitle: 'Senior Engineer',
  topOpportunityCompany: 'Acme',
  topOpportunityScore: 85,
  heatingCompanies: ['TechCo'],
  coolingCompanies: [],
  sourceInterviewRates: { GREENHOUSE: 0.3 },
};

describe('Digest page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders digest data when available', async () => {
    vi.mocked(api.jobs.getDailyDigest).mockResolvedValue(mockDigest);

    render(<Digest />);

    await waitFor(() => {
      expect(screen.getByText(/Senior Engineer/)).toBeInTheDocument();
    });

    expect(screen.getByText('Acme')).toBeInTheDocument();
    expect(screen.getByText('TechCo')).toBeInTheDocument();
  });

  it('shows graceful message on 404 (no digest available)', async () => {
    const err = new (vi.mocked(ApiError))(404, 'Not found');
    vi.mocked(api.jobs.getDailyDigest).mockRejectedValue(err);

    render(<Digest />);

    await waitFor(() => {
      expect(screen.getByText('No digest available yet')).toBeInTheDocument();
    });

    // Should NOT show error styling
    expect(screen.queryByText('Not found')).not.toBeInTheDocument();
  });

  it('shows error message on non-404 failures', async () => {
    vi.mocked(api.jobs.getDailyDigest).mockRejectedValue(new Error('Server error'));

    render(<Digest />);

    await waitFor(() => {
      expect(screen.getByText('Server error')).toBeInTheDocument();
    });
  });

  it('shows empty state when digest is null and no error', async () => {
    vi.mocked(api.jobs.getDailyDigest).mockRejectedValue(
      new (vi.mocked(ApiError))(404, ''),
    );

    render(<Digest />);

    await waitFor(() => {
      expect(screen.getByText('No digest available yet')).toBeInTheDocument();
    });
  });
});
