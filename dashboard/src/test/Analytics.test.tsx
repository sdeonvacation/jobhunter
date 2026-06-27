import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import Analytics from '../pages/Analytics';

vi.mock('../api/careerOps', () => ({
  careerOps: {
    getPatterns: vi.fn(),
  },
}));

import { careerOps } from '../api/careerOps';

const mockPatterns = () => ({
  funnel: {
    totalEvaluated: 200,
    applied: 30,
    responded: 12,
    interviewing: 8,
    offered: 2,
    rejected: 5,
    applicationRate: 0.15,
    responseRate: 0.4,
    interviewRate: 0.27,
    offerRate: 0.07,
  },
  scoreComparison: {
    avgScorePositiveOutcome: 85.0,
    avgScoreNegativeOutcome: 45.0,
    positiveCount: 10,
    negativeCount: 20,
  },
  blockerAnalysis: [
    { reason: 'Language requirement: German', count: 15 },
    { reason: 'Senior+ only', count: 8 },
  ],
  techStackGaps: [
    { skill: 'Kubernetes', count: 12 },
    { skill: 'Terraform', count: 8 },
  ],
  scoreThreshold: 65,
  archetypeByCompany: { SAP: 5, Delivery_Hero: 3 },
  archetypeByRemoteType: { REMOTE: 12, HYBRID: 8 },
});

describe('Analytics page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    vi.mocked(careerOps.getPatterns).mockReturnValue(new Promise(() => {}));
    render(<Analytics />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows error state on fetch failure', async () => {
    vi.mocked(careerOps.getPatterns).mockRejectedValue(new Error('fail'));
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Failed to load analytics')).toBeInTheDocument();
    });
  });

  it('renders funnel stages', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Discovered')).toBeInTheDocument();
    });
    expect(screen.getByText('Evaluated')).toBeInTheDocument();
    expect(screen.getByText('Applied')).toBeInTheDocument();
    expect(screen.getByText('Interviewing')).toBeInTheDocument();
    expect(screen.getByText('Offered')).toBeInTheDocument();
  });

  it('renders funnel values', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('200')).toBeInTheDocument();
    });
    expect(screen.getByText('80')).toBeInTheDocument();
    expect(screen.getByText('30')).toBeInTheDocument();
  });

  it('renders score comparison sections', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Positive')).toBeInTheDocument();
    });
    expect(screen.getByText('Negative')).toBeInTheDocument();
    expect(screen.getByText('Strong Java match')).toBeInTheDocument();
    expect(screen.getByText('Missing Kubernetes')).toBeInTheDocument();
  });

  it('renders blockers list', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Language requirement: German')).toBeInTheDocument();
    });
    expect(screen.getByText('15×')).toBeInTheDocument();
  });

  it('renders tech stack gaps', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Kubernetes')).toBeInTheDocument();
    });
    expect(screen.getByText('12 jobs')).toBeInTheDocument();
    expect(screen.getByText('Terraform')).toBeInTheDocument();
  });

  it('renders score threshold recommendation', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Score Threshold Recommendation')).toBeInTheDocument();
    });
    expect(screen.getByText('65')).toBeInTheDocument();
    expect(screen.getByText('50')).toBeInTheDocument();
  });

  it('renders archetype breakdown', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Backend')).toBeInTheDocument();
    });
    expect(screen.getByText('Fullstack')).toBeInTheDocument();
    expect(screen.getByText('45')).toBeInTheDocument();
    expect(screen.getByText('avg 72.0')).toBeInTheDocument();
  });

  it('renders date filter input', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue(mockPatterns());
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('Since:')).toBeInTheDocument();
    });
  });

  it('shows empty blockers message when none', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue({
      ...mockPatterns(),
      blockerAnalysis: [],
    });
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('No blockers identified')).toBeInTheDocument();
    });
  });

  it('shows empty tech gaps message when none', async () => {
    vi.mocked(careerOps.getPatterns).mockResolvedValue({
      ...mockPatterns(),
      techStackGaps: [],
    });
    render(<Analytics />);

    await waitFor(() => {
      expect(screen.getByText('No gaps found')).toBeInTheDocument();
    });
  });
});
