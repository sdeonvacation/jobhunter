import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Evaluate from '../pages/Evaluate';

vi.mock('../api/client', () => ({
  fetchApi: vi.fn(),
  api: {
    jobs: {
      getById: vi.fn(),
    },
  },
}));

vi.mock('../api/careerOps', () => ({
  careerOps: {
    getEvaluation: vi.fn(),
    evaluateJob: vi.fn(),
    getLiveness: vi.fn(),
  },
}));

import { careerOps } from '../api/careerOps';
import { api } from '../api/client';

const mockJob = {
  id: 'job-1',
  title: 'Senior Backend Engineer',
  companyName: 'TechCorp',
  location: 'Berlin',
  topSkills: ['Java', 'Spring'],
  matchScore: 75,
  opportunityScore: 80,
  source: 'GREENHOUSE' as const,
};

const mockEvaluation = {
  jobId: 'job-1',
  jobTitle: 'Senior Engineer',
  companyName: 'TestCorp',
  overallScore: 4,
  archetype: 'Backend Specialist',
  legitimacyTier: 'GREEN' as const,
  roleSummary: { title: 'Senior Backend', techStack: ['Java', 'Spring'], mustHaves: ['5yr exp'] },
  cvMatch: { overallFit: 4, strongMatches: ['Java expertise aligns'], gaps: [] },
  levelStrategy: { targetLevel: 'Senior', fit: 3, positioningAdvice: 'Mid-senior fit' },
  compResearch: { companySize: 'Large', techReputation: 'Strong engineering culture' },
  customizationPlan: { keywordsToMirror: ['Spring Boot'], resumeTweaks: ['Highlight Spring Boot'] },
  interviewPlan: { likelyQuestions: ['Distributed systems'], technicalTopics: ['System design'] },
  legitimacy: { tier: 'GREEN', confidence: 5, signals: ['Active on career page'], concerns: [] },
  descriptionFingerprint: 'abc123',
  evaluatedAt: '2026-06-15T10:00:00',
};

const mockLiveness = {
  status: 'ACTIVE' as const,
  checkedAt: '2026-06-15T09:00:00',
  url: 'https://example.com/job',
};

function renderWithRouter(jobId = 'job-1') {
  return render(
    <MemoryRouter initialEntries={[`/evaluate/${jobId}`]}>
      <Routes>
        <Route path="/evaluate/:jobId" element={<Evaluate />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('Evaluate page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    vi.mocked(api.jobs.getById).mockReturnValue(new Promise(() => {}));
    vi.mocked(careerOps.getEvaluation).mockReturnValue(new Promise(() => {}));
    vi.mocked(careerOps.getLiveness).mockReturnValue(new Promise(() => {}));

    renderWithRouter();
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows evaluate button when no evaluation exists', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockRejectedValue(new Error('404'));
    vi.mocked(careerOps.getLiveness).mockRejectedValue(new Error('404'));

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('No evaluation yet')).toBeInTheDocument();
    });
    expect(screen.getByText('Evaluate Job')).toBeInTheDocument();
  });

  it('displays job title and company when loaded', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockResolvedValue(mockEvaluation);
    vi.mocked(careerOps.getLiveness).mockResolvedValue(mockLiveness);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Senior Backend Engineer')).toBeInTheDocument();
    });
    expect(screen.getByText(/TechCorp/)).toBeInTheDocument();
  });

  it('displays evaluation score and archetype', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockResolvedValue(mockEvaluation);
    vi.mocked(careerOps.getLiveness).mockResolvedValue(mockLiveness);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('4')).toBeInTheDocument();
    });
    expect(screen.getByText('Backend Specialist')).toBeInTheDocument();
  });

  it('displays role summary', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockResolvedValue(mockEvaluation);
    vi.mocked(careerOps.getLiveness).mockResolvedValue(mockLiveness);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Senior backend role focusing on microservices')).toBeInTheDocument();
    });
  });

  it('displays liveness badge', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockResolvedValue(mockEvaluation);
    vi.mocked(careerOps.getLiveness).mockResolvedValue(mockLiveness);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    });
  });

  it('shows evaluating state when evaluate button clicked', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockRejectedValue(new Error('404'));
    vi.mocked(careerOps.getLiveness).mockRejectedValue(new Error('404'));
    vi.mocked(careerOps.evaluateJob).mockReturnValue(new Promise(() => {}));

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Evaluate Job')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Evaluate Job'));

    await waitFor(() => {
      expect(screen.getByText('Evaluating...')).toBeInTheDocument();
    });
  });

  it('shows error state on evaluation failure', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockRejectedValue(new Error('404'));
    vi.mocked(careerOps.getLiveness).mockRejectedValue(new Error('404'));
    vi.mocked(careerOps.evaluateJob).mockRejectedValue(new Error('Service error'));

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Evaluate Job')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Evaluate Job'));

    await waitFor(() => {
      expect(screen.getByText('Evaluation failed. Please try again.')).toBeInTheDocument();
    });
  });

  it('renders accordion blocks for evaluation', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getEvaluation).mockResolvedValue(mockEvaluation);
    vi.mocked(careerOps.getLiveness).mockResolvedValue(mockLiveness);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('A. CV Match Analysis')).toBeInTheDocument();
    });
    expect(screen.getByText('B. Level Strategy')).toBeInTheDocument();
    expect(screen.getByText('C. Company Research')).toBeInTheDocument();
    expect(screen.getByText('D. Customization Plan')).toBeInTheDocument();
    expect(screen.getByText('E. Interview Plan')).toBeInTheDocument();
    expect(screen.getByText('F. Legitimacy Check')).toBeInTheDocument();
  });
});
