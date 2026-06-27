import { useState, useEffect, useCallback } from 'react';
import type { InterviewStory } from '../types/careerOps';
import { careerOps } from '../api/careerOps';

interface StoryFormData {
  situation: string;
  task: string;
  action: string;
  result: string;
  reflection: string;
  tags: string;
}

const EMPTY_FORM: StoryFormData = {
  situation: '',
  task: '',
  action: '',
  result: '',
  reflection: '',
  tags: '',
};

function truncate(text: string, maxLen: number): string {
  return text.length > maxLen ? text.slice(0, maxLen) + '...' : text;
}

export default function StoryBank() {
  const [stories, setStories] = useState<InterviewStory[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState<StoryFormData>(EMPTY_FORM);
  const [submitting, setSubmitting] = useState(false);
  const [filterTag, setFilterTag] = useState<string>('');

  const fetchStories = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await careerOps.getStories();
      setStories(result);
    } catch (err) {
      setError('Failed to load stories');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchStories();
  }, [fetchStories]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.situation || !form.action || !form.result) return;
    setSubmitting(true);
    setError(null);
    try {
      const tags = form.tags
        .split(',')
        .map((t) => t.trim())
        .filter(Boolean);
      const story = await careerOps.addStory({
        situation: form.situation,
        task: form.task || undefined,
        action: form.action,
        result: form.result,
        reflection: form.reflection || undefined,
        tags,
        skills: [],
      });
      setStories((prev) => [story, ...prev]);
      setForm(EMPTY_FORM);
      setShowForm(false);
    } catch (err) {
      setError('Failed to add story');
      console.error(err);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await careerOps.deleteStory(id);
      setStories((prev) => prev.filter((s) => s.id !== id));
    } catch (err) {
      console.error('Failed to delete story', err);
    }
  };

  const allTags = Array.from(new Set(stories.flatMap((s) => s.tags))).sort();
  const filtered = filterTag
    ? stories.filter((s) => s.tags.includes(filterTag))
    : stories;

  return (
    <div>
      {/* Header */}
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-text-primary">Story Bank</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="bg-accent hover:bg-accent/90 text-white px-4 py-2 rounded-lg font-medium text-sm transition-colors"
        >
          {showForm ? 'Cancel' : 'Add Story'}
        </button>
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 mb-6 text-danger text-sm">
          {error}
        </div>
      )}

      {/* Add Story Form */}
      {showForm && (
        <form
          onSubmit={handleSubmit}
          className="bg-surface-800 rounded-xl border border-surface-700 p-5 mb-6 space-y-4 animate-slide-up"
        >
          <div>
            <label className="block text-xs uppercase tracking-wide text-text-muted mb-1 font-medium">
              Situation *
            </label>
            <textarea
              value={form.situation}
              onChange={(e) => setForm({ ...form, situation: e.target.value })}
              className="w-full bg-surface-700 border border-surface-600 rounded-lg px-3 py-2 text-text-primary text-sm resize-none focus:outline-none focus:border-accent"
              rows={2}
              required
            />
          </div>
          <div>
            <label className="block text-xs uppercase tracking-wide text-text-muted mb-1 font-medium">
              Task
            </label>
            <textarea
              value={form.task}
              onChange={(e) => setForm({ ...form, task: e.target.value })}
              className="w-full bg-surface-700 border border-surface-600 rounded-lg px-3 py-2 text-text-primary text-sm resize-none focus:outline-none focus:border-accent"
              rows={2}
            />
          </div>
          <div>
            <label className="block text-xs uppercase tracking-wide text-text-muted mb-1 font-medium">
              Action *
            </label>
            <textarea
              value={form.action}
              onChange={(e) => setForm({ ...form, action: e.target.value })}
              className="w-full bg-surface-700 border border-surface-600 rounded-lg px-3 py-2 text-text-primary text-sm resize-none focus:outline-none focus:border-accent"
              rows={2}
              required
            />
          </div>
          <div>
            <label className="block text-xs uppercase tracking-wide text-text-muted mb-1 font-medium">
              Result *
            </label>
            <textarea
              value={form.result}
              onChange={(e) => setForm({ ...form, result: e.target.value })}
              className="w-full bg-surface-700 border border-surface-600 rounded-lg px-3 py-2 text-text-primary text-sm resize-none focus:outline-none focus:border-accent"
              rows={2}
              required
            />
          </div>
          <div>
            <label className="block text-xs uppercase tracking-wide text-text-muted mb-1 font-medium">
              Reflection
            </label>
            <textarea
              value={form.reflection}
              onChange={(e) => setForm({ ...form, reflection: e.target.value })}
              className="w-full bg-surface-700 border border-surface-600 rounded-lg px-3 py-2 text-text-primary text-sm resize-none focus:outline-none focus:border-accent"
              rows={2}
            />
          </div>
          <div>
            <label className="block text-xs uppercase tracking-wide text-text-muted mb-1 font-medium">
              Tags (comma-separated)
            </label>
            <input
              type="text"
              value={form.tags}
              onChange={(e) => setForm({ ...form, tags: e.target.value })}
              className="w-full bg-surface-700 border border-surface-600 rounded-lg px-3 py-2 text-text-primary text-sm focus:outline-none focus:border-accent"
              placeholder="leadership, conflict-resolution, scaling"
            />
          </div>
          <button
            type="submit"
            disabled={submitting || !form.situation || !form.action || !form.result}
            className="bg-accent hover:bg-accent/90 text-white px-5 py-2 rounded-lg font-medium text-sm transition-colors disabled:opacity-50"
          >
            {submitting ? 'Saving...' : 'Save Story'}
          </button>
        </form>
      )}

      {/* Tag filter */}
      {allTags.length > 0 && (
        <div className="flex flex-wrap gap-2 mb-6">
          <button
            onClick={() => setFilterTag('')}
            className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
              filterTag === ''
                ? 'bg-accent text-white'
                : 'bg-surface-700 text-text-muted hover:text-text-secondary'
            }`}
          >
            All
          </button>
          {allTags.map((tag) => (
            <button
              key={tag}
              onClick={() => setFilterTag(tag)}
              className={`px-3 py-1 rounded-full text-xs font-medium transition-colors ${
                filterTag === tag
                  ? 'bg-accent text-white'
                  : 'bg-surface-700 text-text-muted hover:text-text-secondary'
              }`}
            >
              {tag}
            </button>
          ))}
        </div>
      )}

      {/* Story list */}
      {loading ? (
        <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-16 animate-fade-in">
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            <svg width="40" height="40" viewBox="0 0 40 40" fill="none" className="text-accent">
              <rect x="10" y="8" width="20" height="24" rx="2" stroke="currentColor" strokeWidth="1.5" opacity="0.3" />
              <path d="M15 16h10M15 20h10M15 24h6" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-secondary text-lg mb-2 font-medium">No stories yet</p>
          <p className="text-text-muted text-sm max-w-xs mx-auto">
            Add STAR stories to build your interview prep library.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((story, i) => (
            <div
              key={story.id}
              className="bg-surface-800 rounded-xl border border-surface-700 p-4 animate-slide-up"
              style={{ animationDelay: `${i * 50}ms` }}
            >
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <p className="text-text-primary text-sm font-medium mb-1">
                    {truncate(story.situation, 120)}
                  </p>
                  <p className="text-text-secondary text-sm mb-1">
                    <span className="text-text-muted">Action:</span> {truncate(story.action, 100)}
                  </p>
                  <p className="text-text-secondary text-sm mb-2">
                    <span className="text-text-muted">Result:</span> {truncate(story.result, 100)}
                  </p>
                  {story.tags.length > 0 && (
                    <div className="flex flex-wrap gap-1.5">
                      {story.tags.map((tag) => (
                        <span
                          key={tag}
                          className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-accent/10 text-accent"
                        >
                          {tag}
                        </span>
                      ))}
                    </div>
                  )}
                </div>
                <button
                  onClick={() => handleDelete(story.id)}
                  className="shrink-0 text-text-muted hover:text-danger transition-colors p-1"
                  title="Delete story"
                >
                  <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                    <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
                  </svg>
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Count */}
      {stories.length > 0 && (
        <div className="mt-4 text-center text-xs text-text-muted">
          {filtered.length} of {stories.length} stories
        </div>
      )}
    </div>
  );
}
