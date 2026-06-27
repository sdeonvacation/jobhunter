import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import FollowUps from '../pages/FollowUps';
import type { FollowUpSchedule, FollowUpStatus } from '../types/careerOps';

vi.mock('../api/careerOps', () => ({
  careerOps: {
    getFollowUps: vi.fn(),
    markFollowUpSent: vi.fn(),
  },
}));

import { careerOps } from '../api/careerOps';

const mockSchedule = (overrides: Partial<FollowUpSchedule> = {}): FollowUpSchedule => ({
  followUps: [
    {
      id: 'fu-1',
      jobId: 'job-1',
      jobTitle: 'Senior Engineer',
      companyName: 'TechCorp',
      scheduledDate: '2026-06-10T00:00:00',
      count: 1,
      status: 'OVERDUE' as FollowUpStatus,
    },
    {
      id: 'fu-2',
      jobId: 'job-2',
      jobTitle: 'Backend Developer',
      companyName: 'StartupInc',
      scheduledDate: '2026-06-20T00:00:00',
      count: 2,
      status: 'PENDING' as FollowUpStatus,
    },
  ],
  total: 2,
  overdueCount: 1,
  ...overrides,
});

describe('FollowUps page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    vi.mocked(careerOps.getFollowUps).mockReturnValue(new Promise(() => {}));
    render(<FollowUps />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('renders follow-up items', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('Senior Engineer')).toBeInTheDocument();
    });
    expect(screen.getByText('Backend Developer')).toBeInTheDocument();
    expect(screen.getByText('TechCorp')).toBeInTheDocument();
    expect(screen.getByText('StartupInc')).toBeInTheDocument();
  });

  it('shows overdue count badge', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('1 overdue')).toBeInTheDocument();
    });
  });

  it('displays status badges', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('OVERDUE')).toBeInTheDocument();
    });
    expect(screen.getByText('PENDING')).toBeInTheDocument();
  });

  it('shows empty state when no follow-ups', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue({ followUps: [], total: 0, overdueCount: 0 });
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('No follow-ups')).toBeInTheDocument();
    });
  });

  it('shows error state on fetch failure', async () => {
    vi.mocked(careerOps.getFollowUps).mockRejectedValue(new Error('Network error'));
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('Failed to load follow-ups')).toBeInTheDocument();
    });
  });

  it('renders Mark Sent buttons for non-sent items', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    render(<FollowUps />);

    await waitFor(() => {
      const buttons = screen.getAllByText('Mark Sent');
      expect(buttons.length).toBe(2);
    });
  });

  it('does not render Mark Sent button for sent items', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule({
      followUps: [
        { id: 'fu-3', jobId: 'job-3', jobTitle: 'DevOps', companyName: 'CloudCo', scheduledDate: '2026-06-05T00:00:00', count: 1, status: 'SENT' as FollowUpStatus },
      ],
    }));
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('DevOps')).toBeInTheDocument();
    });
    expect(screen.queryByText('Mark Sent')).not.toBeInTheDocument();
  });

  it('calls markFollowUpSent when Mark Sent clicked', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    vi.mocked(careerOps.markFollowUpSent).mockResolvedValue(undefined);
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('Senior Engineer')).toBeInTheDocument();
    });

    const buttons = screen.getAllByText('Mark Sent');
    fireEvent.click(buttons[0]);

    await waitFor(() => {
      expect(careerOps.markFollowUpSent).toHaveBeenCalledWith('fu-1');
    });
  });

  it('switches tabs and refetches', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText('Senior Engineer')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Overdue'));

    await waitFor(() => {
      expect(careerOps.getFollowUps).toHaveBeenCalledWith('OVERDUE');
    });
  });

  it('shows total count footer', async () => {
    vi.mocked(careerOps.getFollowUps).mockResolvedValue(mockSchedule());
    render(<FollowUps />);

    await waitFor(() => {
      expect(screen.getByText(/Showing 2 of 2 follow-ups/)).toBeInTheDocument();
    });
  });
});
