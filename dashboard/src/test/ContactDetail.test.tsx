import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ContactDetail from '../pages/ContactDetail';
import { api } from '../api/client';
import type { ContactDetail as ContactDetailType } from '../types';

vi.mock('../api/client', () => ({
  api: {
    people: {
      getById: vi.fn(),
      recordEvent: vi.fn(),
    },
  },
}));

const mockContactDetail: ContactDetailType = {
  id: 'contact-1',
  personName: 'Sarah Engineering Manager',
  title: 'Engineering Manager - Platform',
  linkedinUrl: 'https://linkedin.com/in/sarah-em',
  companyId: 'comp-1',
  companyName: 'BigTech Inc',
  seniority: 'MANAGER',
  discoveredVia: 'JOB_POSTER',
  connectionStatus: 'CONNECTED',
  interviewGenerationWeight: 75,
  warmthScore: 60,
  contactPriorityScore: 82,
  relationshipStatus: 'CONTACTED',
  lastContactAt: '2024-03-10T14:00:00Z',
  createdAt: '2024-01-20T09:00:00Z',
  location: 'Berlin, Germany',
  techStack: ['Java', 'Kubernetes', 'AWS'],
  events: [
    {
      id: 'ev-1',
      eventType: 'CONTACT_DISCOVERED',
      occurredAt: '2024-01-20T09:00:00Z',
    },
    {
      id: 'ev-2',
      eventType: 'MESSAGE_SENT',
      occurredAt: '2024-03-10T14:00:00Z',
    },
  ],
  messages: [
    {
      id: 'msg-1',
      direction: 'OUT',
      channel: 'LINKEDIN',
      messageType: 'CONNECTION_NOTE',
      content: 'Hi Sarah, I noticed the Platform Engineer role...',
      sentAt: '2024-03-10T14:00:00Z',
      replied: true,
      repliedAt: '2024-03-11T10:30:00Z',
    },
    {
      id: 'msg-2',
      direction: 'IN',
      channel: 'LINKEDIN',
      messageType: 'REPLY',
      content: 'Thanks for reaching out! Let me check internally.',
      sentAt: '2024-03-11T10:30:00Z',
      replied: false,
    },
  ],
  linkedJobs: [
    {
      id: 'job-1',
      title: 'Senior Platform Engineer',
      companyName: 'BigTech Inc',
      location: 'Berlin',
      postedDate: '2024-01-15T00:00:00Z',
    },
  ],
};

function renderWithRoute(id: string) {
  return render(
    <MemoryRouter initialEntries={[`/people/${id}`]}>
      <Routes>
        <Route path="/people/:id" element={<ContactDetail />} />
        <Route path="/people" element={<div>People List</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ContactDetail page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders contact header with name, title, company', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Sarah Engineering Manager')).toBeInTheDocument();
    });
    expect(screen.getByText('Engineering Manager - Platform')).toBeInTheDocument();
    expect(screen.getByText('BigTech Inc')).toBeInTheDocument();
  });

  it('displays seniority and connection status badges', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('MANAGER')).toBeInTheDocument();
    });
    expect(screen.getByText('CONNECTED')).toBeInTheDocument();
  });

  it('shows location when present', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Berlin, Germany')).toBeInTheDocument();
    });
  });

  it('renders score gauges with correct values', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('75.0')).toBeInTheDocument(); // IGW
    });
    expect(screen.getByText('60.0')).toBeInTheDocument(); // warmth
    expect(screen.getByText('82.0')).toBeInTheDocument(); // priority
  });

  it('renders tech stack badges', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Java')).toBeInTheDocument();
    });
    expect(screen.getByText('Kubernetes')).toBeInTheDocument();
    expect(screen.getByText('AWS')).toBeInTheDocument();
  });

  it('renders timeline events', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Discovered')).toBeInTheDocument();
    });
    expect(screen.getByText('Message Sent')).toBeInTheDocument();
  });

  it('renders messages with direction badges', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Hi Sarah, I noticed the Platform Engineer role...')).toBeInTheDocument();
    });
    expect(screen.getByText('Thanks for reaching out! Let me check internally.')).toBeInTheDocument();
    expect(screen.getAllByText('OUT')).toHaveLength(1);
    expect(screen.getAllByText('IN')).toHaveLength(1);
  });

  it('renders linked jobs', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Senior Platform Engineer')).toBeInTheDocument();
    });
  });

  it('shows error state on API failure', async () => {
    vi.mocked(api.people.getById).mockRejectedValue(new Error('Not found'));

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Not found')).toBeInTheDocument();
    });
  });

  it('shows LinkedIn link', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      const link = screen.getByRole('link', { name: 'LinkedIn' });
      expect(link).toHaveAttribute('href', 'https://linkedin.com/in/sarah-em');
      expect(link).toHaveAttribute('target', '_blank');
    });
  });

  it('renders Record Event button and dropdown', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Record Event')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Record Event'));

    expect(screen.getByText('Message Sent')).toBeInTheDocument();
    expect(screen.getByText('Call Booked')).toBeInTheDocument();
    expect(screen.getByText('Referral Requested')).toBeInTheDocument();
  });

  it('records event on dropdown item click', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);
    vi.mocked(api.people.recordEvent).mockResolvedValue({
      id: 'ev-new',
      eventType: 'CALL_BOOKED',
      occurredAt: '2024-03-15T10:00:00Z',
    });

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Record Event')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Record Event'));
    fireEvent.click(screen.getByText('Call Booked'));

    await waitFor(() => {
      expect(api.people.recordEvent).toHaveBeenCalledWith('contact-1', {
        eventType: 'CALL_BOOKED',
      });
    });
  });

  it('shows back navigation button', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Back to People')).toBeInTheDocument();
    });
  });

  it('hides tech stack section when empty', async () => {
    vi.mocked(api.people.getById).mockResolvedValue({
      ...mockContactDetail,
      techStack: [],
    });

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('Sarah Engineering Manager')).toBeInTheDocument();
    });
    expect(screen.queryByText('Tech Stack')).not.toBeInTheDocument();
  });

  it('shows empty state for events and messages when none exist', async () => {
    vi.mocked(api.people.getById).mockResolvedValue({
      ...mockContactDetail,
      events: [],
      messages: [],
      linkedJobs: [],
    });

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText('No events recorded')).toBeInTheDocument();
    });
    expect(screen.getByText('No messages yet')).toBeInTheDocument();
  });

  it('shows replied indicator on messages', async () => {
    vi.mocked(api.people.getById).mockResolvedValue(mockContactDetail);

    renderWithRoute('contact-1');

    await waitFor(() => {
      expect(screen.getByText(/Replied/)).toBeInTheDocument();
    });
  });
});
