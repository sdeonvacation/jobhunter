import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import type { CoverLetterResult, CoverLetterTone } from '../types/careerOps';
import type { Job } from '../types';
import { careerOps } from '../api/careerOps';
import { api } from '../api/client';

const TONES: { value: CoverLetterTone; label: string }[] = [
  { value: 'PROFESSIONAL', label: 'Professional' },
  { value: 'CONVERSATIONAL', label: 'Conversational' },
  { value: 'ENTHUSIASTIC', label: 'Enthusiastic' },
];

export default function CoverLetter() {
  const { jobId } = useParams<{ jobId: string }>();
  const [job, setJob] = useState<Job | null>(null);
  const [letters, setLetters] = useState<CoverLetterResult[]>([]);
  const [loading, setLoading] = useState(true);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tone, setTone] = useState<CoverLetterTone>('PROFESSIONAL');
  const [focus, setFocus] = useState('');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    if (!jobId) return;
    setLoading(true);
    try {
      const [jobData, lettersData] = await Promise.allSettled([
        api.jobs.getById(jobId),
        careerOps.getCoverLetters(jobId),
      ]);
      if (jobData.status === 'fulfilled') setJob(jobData.value);
      if (lettersData.status === 'fulfilled') setLetters(lettersData.value);
    } catch (err) {
      setError('Failed to load cover letters');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleGenerate = async () => {
    if (!jobId) return;
    setGenerating(true);
    setError(null);
    try {
      const opts: { tone?: CoverLetterTone; focus?: string } = { tone };
      if (focus.trim()) opts.focus = focus.trim();
      const result = await careerOps.generateCoverLetter(jobId, opts);
      setLetters((prev) => [result, ...prev]);
    } catch (err) {
      setError('Failed to generate cover letter. Please try again.');
      console.error(err);
    } finally {
      setGenerating(false);
    }
  };

  const handleDelete = async (coverId: string) => {
    if (!jobId) return;
    try {
      await careerOps.deleteCoverLetter(jobId, coverId);
      setLetters((prev) => prev.filter((l) => l.id !== coverId));
    } catch (err) {
      setError('Failed to delete cover letter');
      console.error(err);
    }
  };

  const handleCopy = (letter: CoverLetterResult) => {
    navigator.clipboard.writeText(letter.content);
    setCopiedId(letter.id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  if (loading) {
    return <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>;
  }

  return (
    <div>
      {/* Header */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-text-primary">Cover Letter</h1>
        {job && (
          <p className="text-text-secondary mt-1">
            {job.title} · {job.companyName}
          </p>
        )}
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 mb-6 text-danger text-sm">
          {error}
        </div>
      )}

      {/* Generate controls */}
      <div className="bg-surface-800 border border-surface-600 rounded-lg p-4 mb-6">
        <div className="flex items-end gap-4 flex-wrap">
          <div>
            <label className="block text-xs text-text-muted mb-1.5">Tone</label>
            <select
              value={tone}
              onChange={(e) => setTone(e.target.value as CoverLetterTone)}
              className="bg-surface-700 border border-surface-600 text-text-primary rounded-md px-3 py-2 text-sm focus:outline-none focus:border-accent/40"
            >
              {TONES.map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
          </div>
          <div className="flex-1 min-w-[200px]">
            <label className="block text-xs text-text-muted mb-1.5">Focus (optional)</label>
            <input
              type="text"
              value={focus}
              onChange={(e) => setFocus(e.target.value)}
              placeholder="e.g. highlight backend experience, emphasize leadership"
              className="w-full bg-surface-700 border border-surface-600 text-text-primary rounded-md px-3 py-2 text-sm placeholder:text-text-muted focus:outline-none focus:border-accent/40"
            />
          </div>
          <button
            onClick={handleGenerate}
            disabled={generating}
            className="bg-accent hover:bg-accent/90 disabled:opacity-50 disabled:cursor-not-allowed text-white rounded-md px-4 py-2 text-sm font-medium transition-colors"
          >
            {generating ? 'Generating...' : 'Generate'}
          </button>
        </div>
      </div>

      {/* Generating state */}
      {generating && (
        <div className="text-center py-10">
          <div className="mx-auto w-12 h-12 mb-4 rounded-full bg-accent/10 flex items-center justify-center animate-pulse-soft">
            <svg width="24" height="24" viewBox="0 0 32 32" fill="none" className="text-accent animate-spin">
              <circle cx="16" cy="16" r="12" stroke="currentColor" strokeWidth="2" opacity="0.3" />
              <path d="M16 4a12 12 0 0 1 12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-secondary font-medium">Generating cover letter...</p>
          <p className="text-text-muted text-sm mt-1">This may take 10-20 seconds</p>
        </div>
      )}

      {/* Cover letters list */}
      {letters.length === 0 && !generating && (
        <div className="text-center py-16 animate-fade-in">
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            <svg width="36" height="36" viewBox="0 0 24 24" fill="none" className="text-text-muted">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M14 2v6h6M8 13h8M8 17h8M8 9h2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </div>
          <p className="text-text-secondary text-lg font-medium">No cover letters yet</p>
          <p className="text-text-muted text-sm mt-1">Generate one using the controls above</p>
        </div>
      )}

      <div className="space-y-4">
        {letters.map((letter, i) => (
          <div
            key={letter.id}
            className="bg-surface-800 border border-surface-600 rounded-lg p-4 animate-slide-up"
            style={{ animationDelay: `${i * 50}ms` }}
          >
            {/* Card header */}
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2">
                <span className="text-xs px-2 py-0.5 rounded-full bg-accent/10 text-accent ring-1 ring-accent/20 font-medium">
                  {letter.tone}
                </span>
                <span className="text-xs text-text-muted">v{letter.version}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs text-text-muted">
                  {new Date(letter.generatedAt).toLocaleString()}
                </span>
                <button
                  onClick={() => handleCopy(letter)}
                  title="Copy to clipboard"
                  className="w-7 h-7 flex items-center justify-center rounded-md border border-surface-600 text-text-muted hover:border-accent/40 hover:text-accent hover:bg-accent/10 transition-all duration-150"
                >
                  {copiedId === letter.id ? (
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polyline points="3.5 8 6.5 11 12.5 5" />
                    </svg>
                  ) : (
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="5" y="5" width="9" height="9" rx="1" />
                      <path d="M3 11V3a1 1 0 0 1 1-1h8" />
                    </svg>
                  )}
                </button>
                <button
                  onClick={() => handleDelete(letter.id)}
                  title="Delete cover letter"
                  className="w-7 h-7 flex items-center justify-center rounded-md border border-surface-600 text-text-muted hover:border-red-500/40 hover:text-red-400 hover:bg-red-500/10 transition-all duration-150"
                >
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M3 4h10M5.5 4V3a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1v1M12 4v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4" />
                  </svg>
                </button>
              </div>
            </div>

            {/* Content */}
            <div className="text-text-secondary text-sm whitespace-pre-wrap leading-relaxed">
              {letter.content}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
