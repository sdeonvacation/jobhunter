import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import InterviewPrep from '../pages/InterviewPrep';
import type { InterviewPrepResult } from '../types/careerOps';
import type { Job } from '../types';

vi.mock('../api/careerOps', () => ({
  careerOps: {
    getInterviewPrep: vi.fn(),
    prepareInterview: vi.fn(),
  },
}));

vi.mock('../api/client', () => ({
  api: {
    jobs: {
      getById: vi.fn(),
    },
  },
  fetchApi: vi.fn(),
}));

import { careerOps } from '../api/careerOps';
import { api } from '../api/client';

const mockPrep: InterviewPrepResult = {
  talkingPoints: ['Discuss microservices experience', 'Mention Kafka expertise', 'Ask about team size'],
  mappedStoryIds: ['story-abc', 'story-def'],
  companyResearch: 'TechCorp is a B2B SaaS company focused on developer tooling.',
};

const mockJob: Partial<Job> = {
  id: 'job-123',
  title: 'Senior Backend Engineer',
  companyName: 'TechCorp',
  location: 'Berlin',
};

function renderWithRouter(jobId = 'job-123') {
  return render(
    <MemoryRouter initialEntries={[`/interview-prep/${jobId}`]}>
      <Routes>
        <Route path="/interview-prep/:jobId" element={<InterviewPrep />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('InterviewPrep page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    vi.mocked(api.jobs.getById).mockReturnValue(new Promise(() => {}));
    vi.mocked(careerOps.getInterviewPrep).mockReturnValue(new Promise(() => {}));
    renderWithRouter();
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows empty state with Generate Prep button when no prep exists', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockRejectedValue(new Error('Not found'));
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('No interview prep yet')).toBeInTheDocument();
    });
    expect(screen.getByText('Generate Prep')).toBeInTheDocument();
  });

  it('renders job title and company in header', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockResolvedValue(mockPrep);
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Senior Backend Engineer')).toBeInTheDocument();
    });
    expect(screen.getByText(/TechCorp/)).toBeInTheDocument();
  });

  it('renders talking points as checklist', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockResolvedValue(mockPrep);
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Discuss microservices experience')).toBeInTheDocument();
    });
    expect(screen.getByText('Mention Kafka expertise')).toBeInTheDocument();
    expect(screen.getByText('Ask about team size')).toBeInTheDocument();
  });

  it('renders mapped story IDs', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockResolvedValue(mockPrep);
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('story-abc')).toBeInTheDocument();
    });
    expect(screen.getByText('story-def')).toBeInTheDocument();
  });

  it('renders company research', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockResolvedValue(mockPrep);
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText(/B2B SaaS company/)).toBeInTheDocument();
    });
  });

  it('toggles checkbox on talking point click', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockResolvedValue(mockPrep);
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Discuss microservices experience')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Discuss microservices experience'));

    await waitFor(() => {
      expect(screen.getByText('Discuss microservices experience')).toHaveClass('line-through');
    });
  });

  it('calls prepareInterview on Generate Prep click', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockRejectedValue(new Error('Not found'));
    vi.mocked(careerOps.prepareInterview).mockResolvedValue(mockPrep);
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate Prep')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Generate Prep'));

    await waitFor(() => {
      expect(careerOps.prepareInterview).toHaveBeenCalledWith('job-123');
    });

    await waitFor(() => {
      expect(screen.getByText('Discuss microservices experience')).toBeInTheDocument();
    });
  });

  it('shows error message on generation failure', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockRejectedValue(new Error('Not found'));
    vi.mocked(careerOps.prepareInterview).mockRejectedValue(new Error('AI error'));
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate Prep')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Generate Prep'));

    await waitFor(() => {
      expect(screen.getByText('Failed to generate interview prep. Please try again.')).toBeInTheDocument();
    });
  });

  it('shows empty mapped stories message when none exist', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob as Job);
    vi.mocked(careerOps.getInterviewPrep).mockResolvedValue({
      ...mockPrep,
      mappedStoryIds: [],
    });
    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('No stories mapped to this job.')).toBeInTheDocument();
    });
  });
});
