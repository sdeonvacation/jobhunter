import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import TechStack from '../components/TechStack';

const mockSkills: string[] = ['Java', 'Spring Boot', 'PostgreSQL', 'Docker', 'TypeScript'];

describe('TechStack', () => {
  it('renders skills heading', () => {
    render(<TechStack skills={mockSkills} />);
    expect(screen.getByText('Skills')).toBeInTheDocument();
  });

  it('renders individual skill names', () => {
    render(<TechStack skills={mockSkills} />);
    expect(screen.getByText('Java')).toBeInTheDocument();
    expect(screen.getByText('Spring Boot')).toBeInTheDocument();
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument();
    expect(screen.getByText('Docker')).toBeInTheDocument();
    expect(screen.getByText('TypeScript')).toBeInTheDocument();
  });

  it('applies accent styling to skill pills', () => {
    const { container } = render(<TechStack skills={mockSkills} />);
    const skillPills = container.querySelectorAll('span[class*="rounded-full"]');
    expect(skillPills.length).toBe(5);
    const javaPill = Array.from(skillPills).find((el) => el.textContent === 'Java');
    expect(javaPill?.className).toContain('bg-accent/10');
    expect(javaPill?.className).toContain('text-accent');
  });

  it('renders empty state gracefully', () => {
    const { container } = render(<TechStack skills={[]} />);
    expect(container.firstChild).toBeEmptyDOMElement();
  });
});
