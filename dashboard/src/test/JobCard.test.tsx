import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import JobCard from '../components/JobCard';
import type { Job } from '../types';

const mockJob: Job = {
  id: 'job-1',
  title: 'Senior Backend Engineer',
  companyName: 'TechCorp',
  location: 'Berlin, Germany',
  remoteType: 'HYBRID',
  applyUrl: 'https://apply.example.com',
  topSkills: ['Java', 'Spring'],
  source: 'GREENHOUSE',
  salaryMin: 80000,
  salaryMax: 120000,
  salaryCurrency: 'EUR',
  opportunityScore: 82,
  matchScore: 75,
  recommendation: 'APPLY',
  postedDate: '2026-05-01',
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
    render(<JobCard job={mockJob} />);
    expect(screen.getByText('Senior Backend Engineer')).toBeInTheDocument();
    expect(screen.getByText('TechCorp')).toBeInTheDocument();
  });

  it('renders as a link to apply URL', () => {
    render(<JobCard job={mockJob} />);
    const link = screen.getByRole('link');
    expect(link).toHaveAttribute('href', 'https://apply.example.com');
    expect(link).toHaveAttribute('target', '_blank');
  });

  it('renders location and remote type', () => {
    render(<JobCard job={mockJob} />);
    expect(screen.getByText('Berlin, Germany')).toBeInTheDocument();
    expect(screen.getByText('HYBRID')).toBeInTheDocument();
  });

  it('renders salary range', () => {
    render(<JobCard job={mockJob} />);
    expect(screen.getByText('EUR 80k-120k')).toBeInTheDocument();
  });

  it('renders opportunity score badge', () => {
    render(<JobCard job={mockJob} />);
    expect(screen.getByText('82')).toBeInTheDocument();
  });

  it('renders recommendation badge with colored dot', () => {
    render(<JobCard job={mockJob} />);
    expect(screen.getByText('APPLY')).toBeInTheDocument();
  });

  it('handles job without optional fields', () => {
    render(<JobCard job={minimalJob} />);
    expect(screen.getByText('Frontend Dev')).toBeInTheDocument();
    expect(screen.getByText('StartupCo')).toBeInTheDocument();
  });

  it('does not render salary when missing', () => {
    const { container } = render(<JobCard job={minimalJob} />);
    expect(container.textContent).not.toContain('EUR');
  });

  it('renders top skills as chips', () => {
    render(<JobCard job={mockJob} />);
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('Spring')).toBeInTheDocument();
  });

  it('does not render skills section when topSkills is empty', () => {
    const { container } = render(<JobCard job={minimalJob} />);
    const skillChips = container.querySelectorAll('[class*="bg-accent/10"]');
    expect(skillChips.length).toBe(0);
  });

  it('applies stagger animation delay based on index', () => {
    const { container } = render(<JobCard job={mockJob} index={3} />);
    const link = container.querySelector('a');
    expect(link?.style.animationDelay).toBe('150ms');
  });

  it('applies hover classes for lift effect', () => {
    const { container } = render(<JobCard job={mockJob} />);
    const link = container.querySelector('a');
    expect(link?.className).toContain('hover:-translate-y-px');
    expect(link?.className).toContain('hover:shadow-glow');
  });

  it('limits displayed skills to 5', () => {
    const manySkillsJob: Job = {
      ...mockJob,
      topSkills: ['Java', 'Spring', 'Kotlin', 'Docker', 'K8s', 'AWS', 'Terraform'],
    };
    render(<JobCard job={manySkillsJob} />);
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('K8s')).toBeInTheDocument();
    expect(screen.queryByText('AWS')).not.toBeInTheDocument();
    expect(screen.queryByText('Terraform')).not.toBeInTheDocument();
  });
});
