import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import TechStack from '../components/TechStack';
import type { JobSkill } from '../types';

const mockSkills: JobSkill[] = [
  { id: '1', skillName: 'Java', category: 'LANGUAGE', isRequired: true },
  { id: '2', skillName: 'Spring Boot', category: 'FRAMEWORK', isRequired: true },
  { id: '3', skillName: 'PostgreSQL', category: 'DATABASE', isRequired: true },
  { id: '4', skillName: 'Docker', category: 'TOOL', isRequired: false, rawMention: 'Docker/K8s' },
  { id: '5', skillName: 'TypeScript', category: 'LANGUAGE', isRequired: false },
];

describe('TechStack', () => {
  it('renders skills grouped by category', () => {
    render(<TechStack skills={mockSkills} />);
    expect(screen.getByText('Languages')).toBeInTheDocument();
    expect(screen.getByText('Frameworks')).toBeInTheDocument();
    expect(screen.getByText('Databases')).toBeInTheDocument();
    expect(screen.getByText('Tools')).toBeInTheDocument();
  });

  it('renders individual skill names', () => {
    render(<TechStack skills={mockSkills} />);
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('Spring Boot')).toBeInTheDocument();
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument();
  });

  it('marks nice-to-have skills with indicator', () => {
    render(<TechStack skills={mockSkills} />);
    // Nice-to-have label rendered as separate span
    const niceLabels = screen.getAllByText('(nice)');
    expect(niceLabels).toHaveLength(2); // Docker and TypeScript
  });

  it('applies accent styling to required skills', () => {
    const { container } = render(<TechStack skills={mockSkills} />);
    const skillPills = container.querySelectorAll('span[class*="rounded-full"]');
    const javaPill = Array.from(skillPills).find((el) => el.textContent?.includes('Java') && !el.textContent?.includes('(nice)'));
    expect(javaPill?.className).toContain('bg-accent/10');
    expect(javaPill?.className).toContain('text-accent');
  });

  it('applies muted styling to non-required skills', () => {
    const { container } = render(<TechStack skills={mockSkills} />);
    const skillPills = container.querySelectorAll('span[class*="rounded-full"]');
    const dockerPill = Array.from(skillPills).find((el) => el.textContent?.includes('Docker'));
    expect(dockerPill?.className).toContain('bg-surface-700');
    expect(dockerPill?.className).toContain('text-text-secondary');
  });

  it('renders empty state gracefully', () => {
    const { container } = render(<TechStack skills={[]} />);
    expect(container.firstChild).toBeEmptyDOMElement();
  });
});
