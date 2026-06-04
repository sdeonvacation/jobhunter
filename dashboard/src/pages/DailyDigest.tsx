import { useState, useEffect, useCallback } from 'react';
import type { Job } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';

export default function DailyDigest() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);

  const fetchToday = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api.jobs.getToday(page, 50);
      setJobs(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (err) {
      console.error('Failed to fetch today jobs', err);
    } finally {
      setLoading(false);
    }
  }, [page]);

  useEffect(() => {
    fetchToday();
  }, [fetchToday]);

  const handleMarkApplied = async (id: string) => {
    try {
      await api.jobs.markApplied(id);
      setJobs((prev) => prev.filter((j) => j.id !== id));
    } catch (err) {
      console.error('Failed to mark applied', err);
    }
  };

  const today = new Date().toLocaleDateString('en-US', {
    weekday: 'long',
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  });

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-1">Daily Digest</h1>
      <p className="text-sm text-text-muted mb-6">{today}</p>

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No new jobs today</p>
          <p className="text-text-muted text-sm">Check back after the next crawl cycle.</p>
        </div>
      ) : (
        <>
          <p className="text-sm text-text-secondary mb-4">{totalElements} new job{totalElements !== 1 ? 's' : ''} discovered today</p>
          <div className="space-y-3">
            {jobs.map((job) => (
              <JobCard key={job.id} job={job} onMarkApplied={handleMarkApplied} />
            ))}
          </div>
        </>
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
