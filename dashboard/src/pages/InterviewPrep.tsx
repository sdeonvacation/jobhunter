import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import type { InterviewPrepResult } from '../types/careerOps';
import type { Job } from '../types';
import { careerOps } from '../api/careerOps';
import { api } from '../api/client';

export default function InterviewPrep() {
  const { jobId } = useParams<{ jobId: string }>();
  const [prep, setPrep] = useState<InterviewPrepResult | null>(null);
  const [job, setJob] = useState<Job | null>(null);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [checkedItems, setCheckedItems] = useState<Set<number>>(new Set());

  const fetchData = useCallback(async () => {
    if (!jobId) return;
    setLoading(true);
    try {
      const [jobData, prepData] = await Promise.allSettled([
        api.jobs.getById(jobId),
        careerOps.getInterviewPrep(jobId),
      ]);
      if (jobData.status === 'fulfilled') setJob(jobData.value);
      if (prepData.status === 'fulfilled') setPrep(prepData.value);
    } catch (err) {
      setError('Failed to load interview prep data');
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
      const result = await careerOps.prepareInterview(jobId);
      setPrep(result);
      setCheckedItems(new Set());
    } catch (err) {
      setError('Failed to generate interview prep. Please try again.');
      console.error(err);
    } finally {
      setGenerating(false);
    }
  };

  const toggleChecked = (index: number) => {
    setCheckedItems((prev) => {
      const next = new Set(prev);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  };

  if (loading) {
    return <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>;
  }

  return (
    <div>
      {/* Header */}
      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-text-primary">
            {job?.title ?? 'Interview Prep'}
          </h1>
          {job && (
            <p className="text-text-secondary mt-1">
              {job.companyName}
              {job.location && <span className="text-text-muted"> · {job.location}</span>}
            </p>
          )}
        </div>
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 mb-6 text-danger text-sm">
          {error}
        </div>
      )}

      {/* No prep yet */}
      {!prep && !generating && (
        <div className="text-center py-16 animate-fade-in">
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            <svg width="40" height="40" viewBox="0 0 40 40" fill="none" className="text-accent">
              <path
                d="M12 20h16M20 12v16"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
              />
            </svg>
          </div>
          <p className="text-text-secondary text-lg mb-4 font-medium">No interview prep yet</p>
          <button
            onClick={handleGenerate}
            className="bg-accent hover:bg-accent/90 text-white px-6 py-2.5 rounded-lg font-medium transition-colors"
          >
            Generate Prep
          </button>
        </div>
      )}

      {/* Generating state */}
      {generating && (
        <div className="text-center py-16">
          <div className="mx-auto w-16 h-16 mb-5 rounded-full bg-accent/10 flex items-center justify-center animate-pulse-soft">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none" className="text-accent animate-spin">
              <circle cx="16" cy="16" r="12" stroke="currentColor" strokeWidth="2" opacity="0.3" />
              <path d="M16 4a12 12 0 0 1 12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-secondary text-lg font-medium">Generating prep...</p>
          <p className="text-text-muted text-sm mt-2">This may take 10-20 seconds</p>
        </div>
      )}

      {/* Prep results */}
      {prep && (
        <div className="space-y-6">
          {/* Talking Points */}
          <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up">
            <h3 className="text-xs uppercase tracking-wide text-text-muted mb-3 font-medium">
              Talking Points
            </h3>
            <ul className="space-y-2">
              {prep.talkingPoints.map((point, i) => (
                <li
                  key={i}
                  className="flex items-start gap-3 cursor-pointer group"
                  onClick={() => toggleChecked(i)}
                >
                  <span
                    className={`mt-0.5 shrink-0 w-5 h-5 rounded border flex items-center justify-center transition-colors ${
                      checkedItems.has(i)
                        ? 'bg-accent border-accent'
                        : 'border-surface-600 group-hover:border-surface-500'
                    }`}
                  >
                    {checkedItems.has(i) && (
                      <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                        <path d="M2 6l3 3 5-5" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      </svg>
                    )}
                  </span>
                  <span
                    className={`text-sm leading-relaxed transition-colors ${
                      checkedItems.has(i) ? 'text-text-muted line-through' : 'text-text-secondary'
                    }`}
                  >
                    {point}
                  </span>
                </li>
              ))}
            </ul>
          </div>

          {/* Mapped Stories */}
          <div
            className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up"
            style={{ animationDelay: '50ms' }}
          >
            <h3 className="text-xs uppercase tracking-wide text-text-muted mb-3 font-medium">
              Mapped Stories
            </h3>
            {prep.mappedStoryIds.length === 0 ? (
              <p className="text-text-muted text-sm">No stories mapped to this job.</p>
            ) : (
              <ul className="space-y-1.5">
                {prep.mappedStoryIds.map((storyId, i) => (
                  <li key={i} className="text-text-secondary text-sm flex gap-2">
                    <span className="text-accent shrink-0">•</span>
                    <span className="font-mono text-xs">{storyId}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* Company Research */}
          <div
            className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up"
            style={{ animationDelay: '100ms' }}
          >
            <h3 className="text-xs uppercase tracking-wide text-text-muted mb-3 font-medium">
              Company Research
            </h3>
            <p className="text-text-secondary text-sm leading-relaxed whitespace-pre-line">
              {prep.companyResearch}
            </p>
          </div>

          {/* Re-generate button */}
          <div className="pt-2">
            <button
              onClick={handleGenerate}
              disabled={generating}
              className="bg-surface-700 hover:bg-surface-600 text-text-secondary text-sm px-4 py-2 rounded-lg transition-colors disabled:opacity-50"
            >
              Regenerate
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
