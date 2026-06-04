import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import JobCard from '../components/JobCard';
import type { Job } from '../types';

const mockJob: Job = {
  id: 'job-1',
  title: 'Senior Backend Engineer',
  companyName: 'TechCorp',
  location: 'Berlin, Germany',
  remoteType: 'HYBRID',
  description: '<p>Build cool stuff</p>',
  applyUrl: 'https://apply.example.com',
  topSkills: ['Java', 'Spring'],
  source: 'GREENHOUSE',
  salaryMin: 80000,
  salaryMax: 120000,
  salaryCurrency: 'EUR',
  salaryPeriod: 'YEARLY',
  opportunityScore: 82,
  matchScore: 75,
  recommendation: 'APPLY',
};

const minimalJob: Job = {
  id: 'job-2',
  title: 'Frontend Dev',
  companyName: 'StartupCo',
  topSkills: [],
  source: 'LEVER',
  opportunityScore: 0,
  matchScore: 0,
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
