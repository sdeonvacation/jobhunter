import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import People from '../pages/People';
import { api } from '../api/client';
import type { Contact, PeoplePage, PeopleStats, RelationshipStatus, Seniority } from '../types';

vi.mock('../api/client', () => ({
  api: {
    people: {
      list: vi.fn(),
      getStats: vi.fn(),
    },
  },
}));

const mockContact = (overrides: Partial<Contact> = {}): Contact => ({
  id: 'c-' + Math.random().toString(36).slice(2, 8),
  personName: 'Jane Recruiter',
  title: 'Senior Technical Recruiter',
  linkedinUrl: 'https://linkedin.com/in/jane',
  companyId: 'comp-1',
  companyName: 'Acme Corp',
  seniority: 'RECRUITER',
  discoveredVia: 'JOB_POSTER',
  connectionStatus: 'NONE',
  interviewGenerationWeight: 45,
  warmthScore: 60,
  contactPriorityScore: 72,
  relationshipStatus: 'DISCOVERED',
  createdAt: '2024-01-15T10:00:00Z',
  ...overrides,
});

const mockPage = (contacts: Contact[], total?: number, pages?: number): PeoplePage => ({
  content: contacts,
  totalElements: total ?? contacts.length,
  totalPages: pages ?? 1,
  number: 0,
  size: 18,
});

const mockStats: PeopleStats = {
  totalContacts: 42,
  byStatus: {
    DISCOVERED: 20,
    CONTACTED: 10,
    REPLIED: 5,
    ENGAGED: 4,
    REFERRED: 2,
    INTERVIEW_OBTAINED: 1,
    GHOSTED: 0,
    COLD: 0,
  } as Record<RelationshipStatus, number>,
  bySeniority: {
    RECRUITER: 15,
    MANAGER: 10,
    DIRECTOR: 5,
    STAFF: 4,
    SENIOR: 6,
    IC: 2,
  } as Record<Seniority, number>,
  avgPriorityScore: 55.3,
  discoveredToday: 3,
};

function renderWithRouter(ui: React.ReactElement) {
  return render(<MemoryRouter>{ui}</MemoryRouter>);
}

describe('People page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.people.getStats).mockResolvedValue(mockStats);
  });

  it('renders contacts in card grid', async () => {
    const contacts = [
      mockContact({ personName: 'Alice Smith', companyName: 'TechCo' }),
      mockContact({ personName: 'Bob Jones', companyName: 'StartupXYZ' }),
    ];
    vi.mocked(api.people.list).mockResolvedValue(mockPage(contacts));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Alice Smith')).toBeInTheDocument();
    });
    expect(screen.getByText('Bob Jones')).toBeInTheDocument();
    expect(screen.getByText('TechCo')).toBeInTheDocument();
    expect(screen.getByText('StartupXYZ')).toBeInTheDocument();
  });

  it('shows empty state when no contacts', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([]));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('No contacts found')).toBeInTheDocument();
    });
  });

  it('shows error on API failure', async () => {
    vi.mocked(api.people.list).mockRejectedValue(new Error('Network error'));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Network error')).toBeInTheDocument();
    });
  });

  it('renders stats bar with totals', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([mockContact()]));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument();
    });
    expect(screen.getByText('3')).toBeInTheDocument(); // discoveredToday
    expect(screen.getByText('55.3')).toBeInTheDocument(); // avgPriorityScore
    expect(screen.getByText('6')).toBeInTheDocument(); // engaged + referred
  });

  it('displays seniority badge on contact card', async () => {
    vi.mocked(api.people.list).mockResolvedValue(
      mockPage([mockContact({ seniority: 'MANAGER' })])
    );

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('MANAGER')).toBeInTheDocument();
    });
  });

  it('displays priority score on contact card', async () => {
    vi.mocked(api.people.list).mockResolvedValue(
      mockPage([mockContact({ contactPriorityScore: 88 })])
    );

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('88')).toBeInTheDocument();
    });
  });

  it('pagination controls show correct info', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([mockContact()], 54, 3));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    });
    expect(screen.getByText('54 contacts')).toBeInTheDocument();
    expect(screen.getByText('Previous')).toBeDisabled();
    expect(screen.getByText('Next')).not.toBeDisabled();
  });

  it('navigates to next page on Next click', async () => {
    vi.mocked(api.people.list)
      .mockResolvedValueOnce(mockPage([mockContact()], 36, 2))
      .mockResolvedValueOnce(mockPage([mockContact()], 36, 2));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Next'));

    await waitFor(() => {
      expect(vi.mocked(api.people.list)).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1, size: 18 })
      );
    });
  });

  it('filters by status when tab clicked', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([mockContact()]));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Jane Recruiter')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /CONTACTED/ }));

    await waitFor(() => {
      expect(vi.mocked(api.people.list)).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'CONTACTED', page: 0 })
      );
    });
  });

  it('resets page when status filter changes', async () => {
    vi.mocked(api.people.list)
      .mockResolvedValueOnce(mockPage([mockContact()], 36, 2))
      .mockResolvedValue(mockPage([mockContact()]));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Page 1 of 2')).toBeInTheDocument();
    });

    // Go to page 2
    fireEvent.click(screen.getByText('Next'));

    await waitFor(() => {
      expect(vi.mocked(api.people.list)).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1 })
      );
    });

    // Switch filter - should reset to page 0
    fireEvent.click(screen.getByRole('button', { name: /REPLIED/ }));

    await waitFor(() => {
      expect(vi.mocked(api.people.list)).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'REPLIED', page: 0 })
      );
    });
  });

  it('changes sort when dropdown value changes', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([mockContact()]));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('Jane Recruiter')).toBeInTheDocument();
    });

    const select = screen.getByDisplayValue('Priority');
    fireEvent.change(select, { target: { value: 'name' } });

    await waitFor(() => {
      expect(vi.mocked(api.people.list)).toHaveBeenCalledWith(
        expect.objectContaining({ sort: 'name' })
      );
    });
  });

  it('shows status counts in tabs when stats available', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([mockContact()]));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText(/\(20\)/)).toBeInTheDocument(); // DISCOVERED count
      expect(screen.getByText(/\(10\)/)).toBeInTheDocument(); // CONTACTED count
    });
  });

  it('singular "contact" for count of 1', async () => {
    vi.mocked(api.people.list).mockResolvedValue(mockPage([mockContact()], 1, 1));

    renderWithRouter(<People />);

    await waitFor(() => {
      expect(screen.getByText('1 contact')).toBeInTheDocument();
    });
  });
});
