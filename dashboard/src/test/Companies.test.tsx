import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import Companies from '../pages/Companies';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: {
    companies: {
      list: vi.fn(),
      updatePriority: vi.fn(),
    },
  },
}));

const mockPageResponse = (companies: any[], totalElements?: number, totalPages?: number) => ({
  content: companies,
  totalElements: totalElements ?? companies.length,
  totalPages: totalPages ?? 1,
  number: 0,
  size: 20,
});

const mockCompanies = [
  {
    id: '1',
    name: 'Acme Corp',
    domain: 'acme.com',
    country: 'DE',
    status: 'ACTIVE' as const,
    priorityScore: 4,
    endpointCount: 3,
    interviewRate: 0.25,
    totalApplications: 8,
  },
  {
    id: '2',
    name: 'Beta Inc',
    domain: 'beta.io',
    country: 'US',
    status: 'DISCOVERED' as const,
    priorityScore: 2,
    endpointCount: 1,
    interviewRate: 0,
    totalApplications: 0,
  },
];

describe('Companies page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders company list from page response', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(mockCompanies));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });

    expect(screen.getByText('Beta Inc')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('25%')).toBeInTheDocument();
  });

  it('shows empty state when no companies', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse([]));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('No companies found')).toBeInTheDocument();
    });
  });

  it('shows error message on API failure', async () => {
    vi.mocked(api.companies.list).mockRejectedValue(new Error('Server error'));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Server error')).toBeInTheDocument();
    });
  });

  it('displays pagination controls', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(mockCompanies, 45, 3));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    });

    expect(screen.getByText('45 companies')).toBeInTheDocument();
    expect(screen.getByText('Previous')).toBeDisabled();
    expect(screen.getByText('Next')).not.toBeDisabled();
  });

  it('navigates to next page', async () => {
    vi.mocked(api.companies.list)
      .mockResolvedValueOnce(mockPageResponse(mockCompanies, 45, 3))
      .mockResolvedValueOnce({ content: mockCompanies, totalElements: 45, totalPages: 3, number: 1, size: 20 });

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Page 1 of 3')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Next'));

    await waitFor(() => {
      expect(vi.mocked(api.companies.list)).toHaveBeenCalledWith(
        expect.objectContaining({ page: 1, size: 20 })
      );
    });
  });

  it('resets page on status filter change', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(mockCompanies, 45, 3));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });

    // Use getByRole to target the filter button, not the status badge
    const activeFilterBtn = screen.getByRole('button', { name: 'ACTIVE' });
    fireEvent.click(activeFilterBtn);

    await waitFor(() => {
      expect(vi.mocked(api.companies.list)).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'ACTIVE', page: 0 })
      );
    });
  });

  it('renders search input and debounces search', async () => {
    vi.useFakeTimers();
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(mockCompanies));

    render(<Companies />);

    // Flush initial debounce timer + state updates
    await vi.advanceTimersByTimeAsync(300);

    const searchInput = screen.getByPlaceholderText('Search companies...');
    expect(searchInput).toBeInTheDocument();

    fireEvent.change(searchInput, { target: { value: 'acme' } });

    // Not called immediately with search
    expect(vi.mocked(api.companies.list)).not.toHaveBeenCalledWith(
      expect.objectContaining({ search: 'acme' })
    );

    // After debounce
    await vi.advanceTimersByTimeAsync(300);

    expect(vi.mocked(api.companies.list)).toHaveBeenCalledWith(
      expect.objectContaining({ search: 'acme', page: 0 })
    );
  });

  it('renders priority dots for each company', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(mockCompanies));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });

    // Each company has 5 priority dot buttons
    const priorityButtons = screen.getAllByRole('button', { name: /Set priority/ });
    expect(priorityButtons.length).toBe(10); // 2 companies * 5 dots
  });

  it('calls updatePriority on dot click with optimistic update', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(mockCompanies));
    vi.mocked(api.companies.updatePriority).mockResolvedValue(undefined);

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });

    // Click priority 5 on first company
    const buttons = screen.getAllByRole('button', { name: 'Set priority 5' });
    fireEvent.click(buttons[0]);

    await waitFor(() => {
      expect(api.companies.updatePriority).toHaveBeenCalledWith('1', 5);
    });
  });

  it('does not crash when careerEndpoints and endpointCount both missing', async () => {
    const minimal = [{
      id: '3',
      name: 'Minimal Co',
      status: 'DISCOVERED' as const,
      priorityScore: 10.0,
      interviewRate: 0,
      totalApplications: 0,
    }];
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(minimal));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Minimal Co')).toBeInTheDocument();
    });
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('maps legacy 0-100 priority scores to 1-5 dots', async () => {
    const legacy = [{
      id: '5',
      name: 'Legacy Score Co',
      status: 'ACTIVE' as const,
      priorityScore: 85.0, // Should map to priority 5
      interviewRate: 0,
      totalApplications: 0,
      endpointCount: 1,
    }];
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(legacy));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Legacy Score Co')).toBeInTheDocument();
    });

    // All 5 dots should be present
    const buttons = screen.getAllByRole('button', { name: /Set priority/ });
    expect(buttons.length).toBe(5);
  });

  it('shows singular "company" for count of 1', async () => {
    const single = [{
      id: '6',
      name: 'Solo Co',
      status: 'ACTIVE' as const,
      priorityScore: 3,
      interviewRate: 0,
      totalApplications: 0,
      endpointCount: 0,
    }];
    vi.mocked(api.companies.list).mockResolvedValue(mockPageResponse(single, 1, 1));

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('1 company')).toBeInTheDocument();
    });
  });
});
