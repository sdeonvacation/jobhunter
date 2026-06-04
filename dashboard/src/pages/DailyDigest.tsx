import { useState, useEffect, useCallback } from 'react';
import type { Job } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';

function getGreeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 17) return 'Good afternoon';
  return 'Good evening';
}

function SkeletonCard() {
  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-5">
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <div className="skeleton h-5 w-64 mb-2" />
          <div className="skeleton h-4 w-40 mb-3" />
          <div className="flex gap-2">
            <div className="skeleton h-3 w-24" />
            <div className="skeleton h-5 w-16 rounded-full" />
          </div>
        </div>
        <div className="flex gap-2 ml-3">
          <div className="skeleton h-7 w-16 rounded-full" />
          <div className="skeleton h-7 w-16 rounded-full" />
        </div>
      </div>
    </div>
  );
}

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

  const applyCount = jobs.filter((j) => j.recommendation === 'APPLY').length;
  const uniqueCompanies = new Set(jobs.map((j) => j.companyName)).size;

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-1 animate-fade-in">
        {getGreeting()}, Sam
      </h1>
      <p className="text-sm text-text-muted mb-6 animate-fade-in">{today}</p>

      {loading ? (
        <div className="space-y-3">
          <SkeletonCard />
          <SkeletonCard />
          <SkeletonCard />
          <SkeletonCard />
        </div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-12 animate-fade-in">
          <p className="text-text-muted text-lg mb-2">No new jobs today</p>
          <p className="text-text-muted text-sm">Check back after the next crawl cycle.</p>
        </div>
      ) : (
        <>
          {/* Summary stats row */}
          <div className="flex items-center gap-4 mb-5 text-sm animate-fade-in">
            <span className="text-text-secondary">
              <span className="font-mono font-medium text-text-primary">{totalElements}</span> new
            </span>
            {applyCount > 0 && (
              <span className="text-success">
                <span className="font-mono font-medium">{applyCount}</span> APPLY recommended
              </span>
            )}
            <span className="text-text-muted">
              <span className="font-mono font-medium text-text-secondary">{uniqueCompanies}</span> companies
            </span>
          </div>

          <div className="space-y-3">
            {jobs.map((job, i) => (
              <JobCard key={job.id} job={job} index={i} onMarkApplied={handleMarkApplied} />
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
