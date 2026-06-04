import { useState, useEffect, useCallback } from 'react';
import type { Job, JobSearchParams } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';

export default function Jobs() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [totalPages, setTotalPages] = useState(0);

  const [params, setParams] = useState<JobSearchParams>({
    query: '',
    location: '',
    minScore: undefined,
    page: 0,
    size: 20,
  });

  const fetchJobs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.jobs.search(params);
      setJobs(result.content);
      setTotalPages(result.totalPages);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch jobs');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    fetchJobs();
  }, [fetchJobs]);

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault();
    setParams((p) => ({ ...p, page: 0 }));
  };

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Job Search</h1>

      <form onSubmit={handleSearch} className="flex gap-3 mb-6">
        <input
          type="text"
          placeholder="Search jobs..."
          value={params.query}
          onChange={(e) => setParams((p) => ({ ...p, query: e.target.value }))}
          className="flex-1 bg-surface-800 border border-surface-600 text-text-primary placeholder:text-text-muted rounded-md px-4 py-2.5 focus:border-accent focus:ring-1 focus:ring-accent/30 outline-none text-sm"
        />
        <input
          type="text"
          placeholder="Location"
          value={params.location}
          onChange={(e) => setParams((p) => ({ ...p, location: e.target.value }))}
          className="w-40 bg-surface-800 border border-surface-600 text-text-primary placeholder:text-text-muted rounded-md px-4 py-2.5 focus:border-accent focus:ring-1 focus:ring-accent/30 outline-none text-sm"
        />
        <button
          type="submit"
          className="bg-accent text-white px-5 py-2.5 rounded-md text-sm font-medium hover:bg-accent-light transition-colors"
        >
          Search
        </button>
      </form>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No jobs found</p>
          <p className="text-text-muted text-sm">Try adjusting your search filters.</p>
        </div>
      ) : (
        <div className="space-y-3">
          {jobs.map((job) => (
            <JobCard key={job.id} job={job} />
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-3 mt-6">
          <button
            disabled={params.page === 0}
            onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) - 1 }))}
            className="bg-surface-800 border border-surface-600 rounded-md px-3 py-1.5 text-text-secondary text-sm hover:bg-surface-700 disabled:opacity-30 transition-colors"
          >
            Previous
          </button>
          <span className="text-sm text-text-muted font-mono">
            {(params.page ?? 0) + 1} / {totalPages}
          </span>
          <button
            disabled={(params.page ?? 0) >= totalPages - 1}
            onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
            className="bg-surface-800 border border-surface-600 rounded-md px-3 py-1.5 text-text-secondary text-sm hover:bg-surface-700 disabled:opacity-30 transition-colors"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
