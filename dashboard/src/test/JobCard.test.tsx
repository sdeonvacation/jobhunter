import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import JobCard from '../components/JobCard';
import type { Job } from '../types';

const mockJob: Job = {
  id: 'job-1',
  title: 'Senior Backend Engineer',
  company: { id: 'c1', name: 'TechCorp' },
  location: 'Berlin, Germany',
  isRemote: 'HYBRID',
  description: '<p>Build cool stuff</p>',
  applyUrl: 'https://apply.example.com',
  isActive: true,
  skills: [],
  source: 'GREENHOUSE',
  createdAt: '2024-01-01T00:00:00',
  salaryMin: 80000,
  salaryMax: 120000,
  salaryCurrency: 'EUR',
  salaryPeriod: 'YEARLY',
  opportunityScore: {
    id: 'os-1',
    score: 82,
    breakdown: { match: 40, company: 20, freshness: 22 },
    computedAt: '2024-01-01T00:00:00',
  },
  matchScore: {
    id: 'ms-1',
    overallScore: 75,
    matchedSkills: ['Java', 'Spring'],
    missingSkills: ['Go'],
    recommendation: 'APPLY',
    scoredAt: '2024-01-01T00:00:00',
  },
};

const minimalJob: Job = {
  id: 'job-2',
  title: 'Frontend Dev',
  company: { id: 'c2', name: 'StartupCo' },
  isActive: true,
  skills: [],
  source: 'LEVER',
  createdAt: '2024-02-01T00:00:00',
};

describe('JobCard', () => {
  it('renders job title and company', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.getByText('Senior Backend Engineer')).toBeInTheDocument();
    expect(screen.getByText('TechCorp')).toBeInTheDocument();
  });

  it('renders location and remote type', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.getByText('Berlin, Germany')).toBeInTheDocument();
    expect(screen.getByText('HYBRID')).toBeInTheDocument();
  });

  it('renders salary range', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.getByText('EUR 80k-120k')).toBeInTheDocument();
  });

  it('renders opportunity score badge', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.getByText('82')).toBeInTheDocument();
    expect(screen.getByText('Opp')).toBeInTheDocument();
  });

  it('renders match score badge', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.getByText('75')).toBeInTheDocument();
  });

  it('renders recommendation badge', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.getByText('APPLY')).toBeInTheDocument();
  });

  it('calls onToggle when clicked', () => {
    const onToggle = vi.fn();
    render(<JobCard job={mockJob} onToggle={onToggle} />);
    fireEvent.click(screen.getByText('Senior Backend Engineer'));
    expect(onToggle).toHaveBeenCalled();
  });

  it('shows description when expanded', () => {
    render(<JobCard job={mockJob} expanded onToggle={() => {}} />);
    expect(screen.getByText('Build cool stuff')).toBeInTheDocument();
  });

  it('shows apply button when expanded and has applyUrl', () => {
    render(<JobCard job={mockJob} expanded onToggle={() => {}} />);
    expect(screen.getByText('Apply Externally')).toBeInTheDocument();
  });

  it('shows track button when onApply provided and expanded', () => {
    render(<JobCard job={mockJob} expanded onToggle={() => {}} onApply={() => {}} />);
    expect(screen.getByText('Track Application')).toBeInTheDocument();
  });

  it('does not show expanded content by default', () => {
    render(<JobCard job={mockJob} onToggle={() => {}} />);
    expect(screen.queryByText('Build cool stuff')).not.toBeInTheDocument();
  });

  it('handles job without optional fields', () => {
    render(<JobCard job={minimalJob} onToggle={() => {}} />);
    expect(screen.getByText('Frontend Dev')).toBeInTheDocument();
    expect(screen.getByText('StartupCo')).toBeInTheDocument();
    expect(screen.queryByText('Opp')).not.toBeInTheDocument();
  });

  it('does not render salary when missing', () => {
    const { container } = render(<JobCard job={minimalJob} onToggle={() => {}} />);
    expect(container.textContent).not.toContain('EUR');
  });

  it('applies hover border class on card', () => {
    const { container } = render(<JobCard job={mockJob} onToggle={() => {}} />);
    const card = container.firstChild as HTMLElement;
    expect(card.className).toContain('hover:border-accent/30');
  });

  it('calls onApply with job id', () => {
    const onApply = vi.fn();
    render(<JobCard job={mockJob} expanded onToggle={() => {}} onApply={onApply} />);
    fireEvent.click(screen.getByText('Track Application'));
    expect(onApply).toHaveBeenCalledWith('job-1');
  });
});
