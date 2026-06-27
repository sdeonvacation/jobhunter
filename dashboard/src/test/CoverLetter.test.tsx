import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import CoverLetter from '../pages/CoverLetter';

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
    getCoverLetters: vi.fn(),
    generateCoverLetter: vi.fn(),
    deleteCoverLetter: vi.fn(),
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

const mockLetter = {
  id: 'cl-1',
  content: 'Dear Hiring Manager,\n\nI am writing to express my interest...',
  tone: 'PROFESSIONAL' as const,
  version: 1,
  generatedAt: '2026-06-15T10:00:00',
};

const mockLetter2 = {
  id: 'cl-2',
  content: 'Hey there! I noticed your posting...',
  tone: 'CONVERSATIONAL' as const,
  version: 2,
  generatedAt: '2026-06-16T10:00:00',
};

function renderWithRouter(jobId = 'job-1') {
  return render(
    <MemoryRouter initialEntries={[`/cover-letter/${jobId}`]}>
      <Routes>
        <Route path="/cover-letter/:jobId" element={<CoverLetter />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('CoverLetter page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    vi.mocked(api.jobs.getById).mockReturnValue(new Promise(() => {}));
    vi.mocked(careerOps.getCoverLetters).mockReturnValue(new Promise(() => {}));

    renderWithRouter();
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('shows empty state when no cover letters exist', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('No cover letters yet')).toBeInTheDocument();
    });
    expect(screen.getByText('Generate one using the controls above')).toBeInTheDocument();
  });

  it('displays job title and company in header', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Senior Backend Engineer · TechCorp')).toBeInTheDocument();
    });
  });

  it('renders existing cover letters', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([mockLetter, mockLetter2]);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText(mockLetter.content)).toBeInTheDocument();
    });
    expect(screen.getByText(mockLetter2.content)).toBeInTheDocument();
    expect(screen.getByText('PROFESSIONAL')).toBeInTheDocument();
    expect(screen.getByText('CONVERSATIONAL')).toBeInTheDocument();
  });

  it('displays version badge for each letter', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([mockLetter]);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('v1')).toBeInTheDocument();
    });
  });

  it('shows generating state when generate clicked', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);
    vi.mocked(careerOps.generateCoverLetter).mockReturnValue(new Promise(() => {}));

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Generate'));

    await waitFor(() => {
      expect(screen.getByText('Generating...')).toBeInTheDocument();
      expect(screen.getByText('Generating cover letter...')).toBeInTheDocument();
    });
  });

  it('adds generated letter to list on success', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);
    vi.mocked(careerOps.generateCoverLetter).mockResolvedValue(mockLetter);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Generate'));

    await waitFor(() => {
      expect(screen.getByText(mockLetter.content)).toBeInTheDocument();
    });
  });

  it('passes selected tone to generate', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);
    vi.mocked(careerOps.generateCoverLetter).mockResolvedValue(mockLetter2);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate')).toBeInTheDocument();
    });

    fireEvent.change(screen.getByDisplayValue('Professional'), {
      target: { value: 'CONVERSATIONAL' },
    });
    fireEvent.click(screen.getByText('Generate'));

    await waitFor(() => {
      expect(careerOps.generateCoverLetter).toHaveBeenCalledWith('job-1', {
        tone: 'CONVERSATIONAL',
      });
    });
  });

  it('passes focus when provided', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);
    vi.mocked(careerOps.generateCoverLetter).mockResolvedValue(mockLetter);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate')).toBeInTheDocument();
    });

    fireEvent.change(screen.getByPlaceholderText(/highlight backend/i), {
      target: { value: 'leadership skills' },
    });
    fireEvent.click(screen.getByText('Generate'));

    await waitFor(() => {
      expect(careerOps.generateCoverLetter).toHaveBeenCalledWith('job-1', {
        tone: 'PROFESSIONAL',
        focus: 'leadership skills',
      });
    });
  });

  it('shows error on generation failure', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);
    vi.mocked(careerOps.generateCoverLetter).mockRejectedValue(new Error('AI failed'));

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText('Generate')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Generate'));

    await waitFor(() => {
      expect(screen.getByText('Failed to generate cover letter. Please try again.')).toBeInTheDocument();
    });
  });

  it('deletes a cover letter', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([mockLetter]);
    vi.mocked(careerOps.deleteCoverLetter).mockResolvedValue(undefined);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText(mockLetter.content)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTitle('Delete cover letter'));

    await waitFor(() => {
      expect(careerOps.deleteCoverLetter).toHaveBeenCalledWith('job-1', 'cl-1');
    });
    await waitFor(() => {
      expect(screen.queryByText(mockLetter.content)).not.toBeInTheDocument();
    });
  });

  it('copies content to clipboard', async () => {
    const mockWriteText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText: mockWriteText } });

    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([mockLetter]);

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText(mockLetter.content)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTitle('Copy to clipboard'));

    expect(mockWriteText).toHaveBeenCalledWith(mockLetter.content);
  });

  it('shows error when delete fails', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([mockLetter]);
    vi.mocked(careerOps.deleteCoverLetter).mockRejectedValue(new Error('Server error'));

    renderWithRouter();

    await waitFor(() => {
      expect(screen.getByText(mockLetter.content)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTitle('Delete cover letter'));

    await waitFor(() => {
      expect(screen.getByText('Failed to delete cover letter')).toBeInTheDocument();
    });
  });

  it('renders tone selector with all options', async () => {
    vi.mocked(api.jobs.getById).mockResolvedValue(mockJob);
    vi.mocked(careerOps.getCoverLetters).mockResolvedValue([]);

    renderWithRouter();

    await waitFor(() => {
      const select = screen.getByDisplayValue('Professional');
      expect(select).toBeInTheDocument();
    });

    const options = screen.getAllByRole('option');
    expect(options).toHaveLength(3);
    expect(options[0]).toHaveTextContent('Professional');
    expect(options[1]).toHaveTextContent('Conversational');
    expect(options[2]).toHaveTextContent('Enthusiastic');
  });
});
