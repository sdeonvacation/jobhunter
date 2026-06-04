import { useState, useEffect, useCallback } from 'react';
import type { Job } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';

export default function Applied() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);

  const fetchApplied = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api.jobs.getApplied(page, 20);
      setJobs(result.content);
      setTotalPages(result.totalPages);
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
    } catch (err) {
      console.error('Failed to undo applied', err);
    }
  };

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Applied</h1>

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No applied jobs yet</p>
          <p className="text-text-muted text-sm">Mark jobs as applied from the Jobs tab.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {jobs.map((job) => (
            <JobCard key={job.id} job={job} onUndoApplied={handleUndoApplied} />
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
