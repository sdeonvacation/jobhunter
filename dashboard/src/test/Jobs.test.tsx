import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import Jobs from '../pages/Jobs';
import { api } from '../api/client';
import type { Job, PageResponse } from '../types';

vi.mock('../api/client', () => ({
  api: {
    jobs: {
      search: vi.fn(),
      markApplied: vi.fn(),
    },
  },
}));

// Suppress fetch for /api/jobs/companies
globalThis.fetch = vi.fn().mockResolvedValue({
  json: () => Promise.resolve([]),
}) as unknown as typeof fetch;

const makeJob = (overrides: Partial<Job>): Job => ({
  id: 'job-' + Math.random().toString(36).slice(2, 8),
  title: 'Engineer',
  companyName: 'Corp',
  topSkills: [],
  source: 'GREENHOUSE',
  opportunityScore: 50,
  matchScore: 60,
  ...overrides,
});

const atsJobs: PageResponse<Job> = {
  content: [makeJob({ id: 'ats-1', title: 'ATS Job', source: 'GREENHOUSE' })],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
};

const linkedinJobs: PageResponse<Job> = {
  content: [makeJob({ id: 'li-1', title: 'LinkedIn Job', source: 'LINKEDIN' })],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
};

const indeedJobs: PageResponse<Job> = {
  content: [makeJob({ id: 'in-1', title: 'Indeed Job', source: 'INDEED' })],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  size: 20,
};

const emptyResponse: PageResponse<Job> = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  number: 0,
  size: 20,
};

describe('Jobs page - source tabs', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.jobs.search).mockResolvedValue(atsJobs);
  });

  it('renders all three tabs', async () => {
    render(<Jobs />);
    await waitFor(() => expect(api.jobs.search).toHaveBeenCalled());
    expect(screen.getByText('ATS')).toBeInTheDocument();
    expect(screen.getByText('Indeed')).toBeInTheDocument();
    expect(screen.getByText('LinkedIn')).toBeInTheDocument();
  });

  it('loads ATS tab by default with source=ats', async () => {
    render(<Jobs />);
    await waitFor(() => expect(api.jobs.search).toHaveBeenCalled());
    expect(api.jobs.search).toHaveBeenCalledWith(
      expect.objectContaining({ source: 'ats' }),
    );
    expect(screen.getByText('ATS Job')).toBeInTheDocument();
  });

  it('switching to LinkedIn tab calls search with source=linkedin', async () => {
    vi.mocked(api.jobs.search)
      .mockResolvedValueOnce(atsJobs)
      .mockResolvedValueOnce(linkedinJobs);

    render(<Jobs />);
    await waitFor(() => expect(screen.getByText('ATS Job')).toBeInTheDocument());

    fireEvent.click(screen.getByText('LinkedIn'));

    await waitFor(() =>
      expect(api.jobs.search).toHaveBeenCalledWith(
        expect.objectContaining({ source: 'linkedin' }),
      ),
    );
    expect(screen.getByText('LinkedIn Job')).toBeInTheDocument();
  });

  it('switching to Indeed tab calls search with source=indeed', async () => {
    vi.mocked(api.jobs.search)
      .mockResolvedValueOnce(atsJobs)
      .mockResolvedValueOnce(indeedJobs);

    render(<Jobs />);
    await waitFor(() => expect(screen.getByText('ATS Job')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Indeed'));

    await waitFor(() =>
      expect(api.jobs.search).toHaveBeenCalledWith(
        expect.objectContaining({ source: 'indeed' }),
      ),
    );
    expect(screen.getByText('Indeed Job')).toBeInTheDocument();
  });

  it('active tab has accent styling', async () => {
    render(<Jobs />);
    await waitFor(() => expect(api.jobs.search).toHaveBeenCalled());

    const atsTab = screen.getByText('ATS').closest('button')!;
    expect(atsTab.className).toContain('bg-accent/20');
    expect(atsTab.className).toContain('text-accent');

    const linkedinTab = screen.getByText('LinkedIn').closest('button')!;
    expect(linkedinTab.className).toContain('text-text-muted');
  });

  it('shows empty state when backend returns no jobs', async () => {
    vi.mocked(api.jobs.search)
      .mockResolvedValueOnce(atsJobs)
      .mockResolvedValueOnce(emptyResponse);

    render(<Jobs />);
    await waitFor(() => expect(screen.getByText('ATS Job')).toBeInTheDocument());

    fireEvent.click(screen.getByText('LinkedIn'));

    await waitFor(() => expect(screen.getByText('No jobs found')).toBeInTheDocument());
  });

  it('resets to page 0 when switching tabs', async () => {
    vi.mocked(api.jobs.search).mockResolvedValue(atsJobs);

    render(<Jobs />);
    await waitFor(() => expect(api.jobs.search).toHaveBeenCalled());

    fireEvent.click(screen.getByText('LinkedIn'));

    await waitFor(() =>
      expect(api.jobs.search).toHaveBeenCalledWith(
        expect.objectContaining({ source: 'linkedin', page: 0 }),
      ),
    );
  });
});
