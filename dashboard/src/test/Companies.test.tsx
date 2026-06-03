import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import Companies from '../pages/Companies';
import { api } from '../api/client';

vi.mock('../api/client', () => ({
  api: {
    companies: {
      list: vi.fn(),
    },
  },
}));

const mockCompanies = [
  {
    id: '1',
    name: 'Acme Corp',
    domain: 'acme.com',
    country: 'DE',
    status: 'ACTIVE' as const,
    priorityScore: 85.5,
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
    priorityScore: 42.0,
    endpointCount: 1,
    interviewRate: 0,
    totalApplications: 0,
  },
];

describe('Companies page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders company list with endpointCount', async () => {
    vi.mocked(api.companies.list).mockResolvedValue(mockCompanies as any);

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Acme Corp')).toBeInTheDocument();
    });

    expect(screen.getByText('Beta Inc')).toBeInTheDocument();
    // endpointCount rendered
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    // Priority score
    expect(screen.getByText('85.5')).toBeInTheDocument();
    // Interview rate
    expect(screen.getByText('25%')).toBeInTheDocument();
  });

  it('shows empty state when no companies', async () => {
    vi.mocked(api.companies.list).mockResolvedValue([]);

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

  it('does not crash when careerEndpoints and endpointCount both missing', async () => {
    const minimal = [{
      id: '3',
      name: 'Minimal Co',
      status: 'DISCOVERED' as const,
      priorityScore: 10.0,
      interviewRate: 0,
      totalApplications: 0,
    }];
    vi.mocked(api.companies.list).mockResolvedValue(minimal as any);

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Minimal Co')).toBeInTheDocument();
    });
    // endpointCount falls back to 0
    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('uses careerEndpoints.length as fallback when endpointCount absent', async () => {
    const withEndpoints = [{
      id: '4',
      name: 'Legacy Co',
      status: 'ACTIVE' as const,
      priorityScore: 50.0,
      interviewRate: 0.5,
      totalApplications: 2,
      careerEndpoints: [
        { id: 'e1', url: 'https://example.com', atsType: 'GREENHOUSE', verified: true, isActive: true },
        { id: 'e2', url: 'https://example.com/2', atsType: 'LEVER', verified: false, isActive: true },
      ],
    }];
    vi.mocked(api.companies.list).mockResolvedValue(withEndpoints as any);

    render(<Companies />);

    await waitFor(() => {
      expect(screen.getByText('Legacy Co')).toBeInTheDocument();
    });
    expect(screen.getByText('2')).toBeInTheDocument();
  });
});
