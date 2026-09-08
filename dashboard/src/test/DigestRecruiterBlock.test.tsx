import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import DigestRecruiterBlock from '../components/DigestRecruiterBlock';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: {
    linkedin: {
      connectContact: vi.fn(),
    },
  },
}));

const highResult = {
  jobUrl: 'https://jobs.example.com/1',
  verdict: 'HIGH' as const,
  confidence: 0.92,
  matchedPosts: [
    {
      postUrl: 'https://linkedin.com/posts/123',
      authorName: 'Jane Recruiter',
      authorTitle: 'Talent Acquisition Lead',
      authorLinkedinUrl: 'https://linkedin.com/in/jane',
      snippet: 'We are hiring a senior backend engineer in Berlin!',
      postedAt: '2 days ago',
    },
  ],
  contactId: 'contact-1',
  callsUsed: 1,
};

describe('DigestRecruiterBlock', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders author name and post link for HIGH result', () => {
    render(<DigestRecruiterBlock result={highResult} />);

    expect(screen.getByText('Recruiter posted')).toBeInTheDocument();
    expect(screen.getByText('Jane Recruiter')).toBeInTheDocument();
    expect(screen.getByText('Talent Acquisition Lead')).toBeInTheDocument();
    expect(screen.getByText('posted 2 days ago')).toBeInTheDocument();

    const link = screen.getByRole('link', { name: 'View post' });
    expect(link).toHaveAttribute('href', 'https://linkedin.com/posts/123');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('renders nothing for NOT_FOUND result', () => {
    const { container } = render(
      <DigestRecruiterBlock
        result={{
          jobUrl: 'https://jobs.example.com/2',
          verdict: 'NOT_FOUND',
          confidence: 0,
          matchedPosts: [],
          contactId: null,
          callsUsed: 1,
        }}
      />,
    );

    expect(container.firstChild).toBeNull();
  });

  it('calls connectContact and shows "Request sent" on SENT', async () => {
    vi.mocked(api.linkedin.connectContact).mockResolvedValue({ status: 'SENT', message: 'ok' });

    render(<DigestRecruiterBlock result={highResult} />);

    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));

    await waitFor(() => {
      expect(api.linkedin.connectContact).toHaveBeenCalledWith('contact-1', expect.any(String));
      expect(screen.getByText('Request sent')).toBeInTheDocument();
    });
  });
});