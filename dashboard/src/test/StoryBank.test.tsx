import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import StoryBank from '../pages/StoryBank';
import type { InterviewStory } from '../types/careerOps';

vi.mock('../api/careerOps', () => ({
  careerOps: {
    getStories: vi.fn(),
    addStory: vi.fn(),
    deleteStory: vi.fn(),
  },
}));

import { careerOps } from '../api/careerOps';

const mockStories: InterviewStory[] = [
  {
    id: 'story-1',
    situation: 'Legacy monolith was causing deploy bottlenecks for the entire team',
    task: 'Break monolith into microservices',
    action: 'Designed service boundaries using domain-driven design',
    result: 'Reduced deploy time from 45min to 5min',
    reflection: 'Should have started with strangler fig pattern',
    tags: ['architecture', 'microservices'],
    skills: ['java', 'kubernetes'],
    createdAt: '2026-06-01T10:00:00',
  },
  {
    id: 'story-2',
    situation: 'Production incident with data loss affecting 500 users',
    action: 'Led incident response, implemented point-in-time recovery',
    result: 'Recovered 99.8% of data within 2 hours',
    tags: ['leadership', 'incident-response'],
    skills: ['postgresql', 'aws'],
    createdAt: '2026-06-05T14:00:00',
  },
];

describe('StoryBank page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows loading state initially', () => {
    vi.mocked(careerOps.getStories).mockReturnValue(new Promise(() => {}));
    render(<StoryBank />);
    expect(screen.getByText('Loading...')).toBeInTheDocument();
  });

  it('renders story cards', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText(/Legacy monolith/)).toBeInTheDocument();
    });
    expect(screen.getByText(/Production incident/)).toBeInTheDocument();
  });

  it('shows action and result on cards', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText(/Designed service boundaries/)).toBeInTheDocument();
    });
    expect(screen.getByText(/Reduced deploy time/)).toBeInTheDocument();
  });

  it('renders tag badges', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText('architecture')).toBeInTheDocument();
    });
    expect(screen.getByText('microservices')).toBeInTheDocument();
    expect(screen.getByText('leadership')).toBeInTheDocument();
  });

  it('shows empty state when no stories', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue([]);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText('No stories yet')).toBeInTheDocument();
    });
  });

  it('shows error state on fetch failure', async () => {
    vi.mocked(careerOps.getStories).mockRejectedValue(new Error('Network error'));
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText('Failed to load stories')).toBeInTheDocument();
    });
  });

  it('filters stories by tag', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText(/Legacy monolith/)).toBeInTheDocument();
    });

    // Click the leadership tag filter
    const tagButtons = screen.getAllByText('leadership');
    // First is the filter button, second is on the card badge
    fireEvent.click(tagButtons[0]);

    await waitFor(() => {
      expect(screen.queryByText(/Legacy monolith/)).not.toBeInTheDocument();
    });
    expect(screen.getByText(/Production incident/)).toBeInTheDocument();
  });

  it('shows "All" filter resets tag filter', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText(/Legacy monolith/)).toBeInTheDocument();
    });

    // Filter by a tag
    const tagButtons = screen.getAllByText('leadership');
    fireEvent.click(tagButtons[0]);

    await waitFor(() => {
      expect(screen.queryByText(/Legacy monolith/)).not.toBeInTheDocument();
    });

    // Click All to reset
    fireEvent.click(screen.getByRole('button', { name: 'All' }));

    await waitFor(() => {
      expect(screen.getByText(/Legacy monolith/)).toBeInTheDocument();
    });
  });

  it('toggles add story form visibility', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue([]);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText('Add Story')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Add Story'));
    expect(screen.getByText('Cancel')).toBeInTheDocument();
    expect(screen.getByLabelText(/Situation/)).toBeInTheDocument();

    fireEvent.click(screen.getByText('Cancel'));
    expect(screen.getByText('Add Story')).toBeInTheDocument();
  });

  it('submits new story form', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue([]);
    const newStory: InterviewStory = {
      id: 'story-new',
      situation: 'New situation',
      action: 'New action taken',
      result: 'Great outcome',
      tags: ['testing'],
      skills: [],
      createdAt: '2026-06-27T10:00:00',
    };
    vi.mocked(careerOps.addStory).mockResolvedValue(newStory);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText('Add Story')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Add Story'));

    fireEvent.change(screen.getByLabelText(/Situation/), { target: { value: 'New situation' } });
    fireEvent.change(screen.getByLabelText(/Action/), { target: { value: 'New action taken' } });
    fireEvent.change(screen.getByLabelText(/Result/), { target: { value: 'Great outcome' } });
    fireEvent.change(screen.getByLabelText(/Tags/), { target: { value: 'testing' } });

    fireEvent.click(screen.getByText('Save Story'));

    await waitFor(() => {
      expect(careerOps.addStory).toHaveBeenCalledWith({
        situation: 'New situation',
        task: undefined,
        action: 'New action taken',
        result: 'Great outcome',
        reflection: undefined,
        tags: ['testing'],
        skills: [],
      });
    });

    await waitFor(() => {
      expect(screen.getByText(/New situation/)).toBeInTheDocument();
    });
  });

  it('calls deleteStory and removes card', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    vi.mocked(careerOps.deleteStory).mockResolvedValue(undefined);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText(/Legacy monolith/)).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByTitle('Delete story');
    fireEvent.click(deleteButtons[0]);

    await waitFor(() => {
      expect(careerOps.deleteStory).toHaveBeenCalledWith('story-1');
    });

    await waitFor(() => {
      expect(screen.queryByText(/Legacy monolith/)).not.toBeInTheDocument();
    });
  });

  it('shows story count footer', async () => {
    vi.mocked(careerOps.getStories).mockResolvedValue(mockStories);
    render(<StoryBank />);

    await waitFor(() => {
      expect(screen.getByText(/2 of 2 stories/)).toBeInTheDocument();
    });
  });
});
