import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import JobCard from '../components/JobCard';
import type { Job } from '../types';

const jobWithUuid: Job = {
  id: 'abcdef12-3456-7890-abcd-ef1234567890',
  title: 'Platform Engineer',
  companyName: 'IdCorp',
  topSkills: ['Go'],
  source: 'GREENHOUSE',
  opportunityScore: 70,
  matchScore: 60,
};

describe('JobCard ID display', () => {
  it('renders first 8 chars of job ID with # prefix', () => {
    render(<JobCard job={jobWithUuid} />);
    expect(screen.getByText('#abcdef12')).toBeInTheDocument();
  });

  it('has full UUID as title attribute for hover', () => {
    render(<JobCard job={jobWithUuid} />);
    const idSpan = screen.getByText('#abcdef12');
    expect(idSpan).toHaveAttribute('title', 'abcdef12-3456-7890-abcd-ef1234567890');
  });

  it('uses monospace font and select-all for easy copy', () => {
    render(<JobCard job={jobWithUuid} />);
    const idSpan = screen.getByText('#abcdef12');
    expect(idSpan.className).toContain('font-mono');
    expect(idSpan.className).toContain('select-all');
  });

  it('renders ID next to company name', () => {
    render(<JobCard job={jobWithUuid} />);
    const companyP = screen.getByText('#abcdef12').parentElement;
    expect(companyP?.textContent).toContain('IdCorp');
    expect(companyP?.textContent).toContain('#abcdef12');
  });

  it('handles short IDs gracefully', () => {
    const shortIdJob: Job = {
      ...jobWithUuid,
      id: 'abc',
    };
    render(<JobCard job={shortIdJob} />);
    expect(screen.getByText('#abc')).toBeInTheDocument();
  });
});
