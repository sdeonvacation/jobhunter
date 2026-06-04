import { useState, useEffect, useCallback } from 'react';
import type { Job } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';

export default function Applied() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);

  const fetchApplied = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api.jobs.getApplied(page, 20);
      setJobs(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (err) {
      console.error('Failed to fetch applied jobs', err);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    fetchApplied();
  }, [fetchApplied]);

  const handleUndoApplied = async (id: string) => {
    try {
      await api.jobs.markApplied(id, false);
      setJobs((prev) => prev.filter((j) => j.id !== id));
      setTotalElements((n) => n - 1);
    } catch (err) {
      console.error('Failed to undo applied', err);
    }
  };

  return (
    <div>
      <div className="flex items-baseline gap-3 mb-6">
        <h1 className="text-2xl font-bold text-text-primary">Applied</h1>
        {totalElements > 0 && (
          <span className="text-sm font-mono text-text-muted bg-surface-700 px-2 py-0.5 rounded-full">
            {totalElements}
          </span>
        )}
      </div>

      {loading ? (
        <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-16 animate-fade-in">
          {/* Checkmark illustration */}
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            <svg width="40" height="40" viewBox="0 0 40 40" fill="none" className="text-accent">
              <circle cx="20" cy="20" r="16" stroke="currentColor" strokeWidth="1.5" opacity="0.3" />
              <path
                d="M13 20l5 5 9-9"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
          </div>
          <p className="text-text-secondary text-lg mb-2 font-medium">No applications yet</p>
          <p className="text-text-muted text-sm max-w-xs mx-auto">
            When you find a great match, mark it as applied from the Daily Digest or Jobs page. Your applications will appear here.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {jobs.map((job, i) => (
            <JobCard key={job.id} job={job} index={i} onUndoApplied={handleUndoApplied} />
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 mt-6">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="bg-surface-800 border border-surface-600 rounded-md px-3 py-1.5 text-text-secondary text-sm hover:bg-surface-700 disabled:opacity-30 transition-colors"
          >
            Previous
          </button>
          <span className="text-sm text-text-muted font-mono">
            {page + 1} / {totalPages}
          </span>
          <button
            disabled={page >= totalPages - 1}
            onClick={() => setPage((p) => p + 1)}
            className="bg-surface-800 border border-surface-600 rounded-md px-3 py-1.5 text-text-secondary text-sm hover:bg-surface-700 disabled:opacity-30 transition-colors"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
