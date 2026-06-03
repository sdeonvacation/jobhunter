import { useState, useEffect, useCallback } from 'react';
import type { Job, JobSearchParams } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';
import TechStack from '../components/TechStack';

export default function Jobs() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedId, setExpandedId] = useState<string | null>(null);
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

  const handleApply = async (jobId: string) => {
    try {
      await api.pipeline.apply(jobId);
      // Could show toast or navigate to pipeline
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to track application');
    }
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Job Search</h1>

      <form onSubmit={handleSearch} className="flex gap-3 mb-6">
        <input
          type="text"
          placeholder="Search jobs..."
          value={params.query}
          onChange={(e) => setParams((p) => ({ ...p, query: e.target.value }))}
          className="flex-1 border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <input
          type="text"
          placeholder="Location"
          value={params.location}
          onChange={(e) => setParams((p) => ({ ...p, location: e.target.value }))}
          className="w-40 border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <input
          type="number"
          placeholder="Min Score"
          value={params.minScore ?? ''}
          onChange={(e) =>
            setParams((p) => ({ ...p, minScore: e.target.value ? Number(e.target.value) : undefined }))
          }
          className="w-28 border border-gray-300 rounded px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <button
          type="submit"
          className="bg-blue-600 text-white px-4 py-2 rounded text-sm hover:bg-blue-700"
        >
          Search
        </button>
      </form>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded p-3 mb-4 text-sm">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-center py-8 text-gray-500">Loading...</div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-8 text-gray-500">No jobs found</div>
      ) : (
        <div className="space-y-3">
          {jobs.map((job) => (
            <div key={job.id}>
              <JobCard
                job={job}
                expanded={expandedId === job.id}
                onToggle={() => setExpandedId(expandedId === job.id ? null : job.id)}
                onApply={handleApply}
              />
              {expandedId === job.id && job.skills.length > 0 && (
                <div className="ml-4 mt-2 p-3 bg-gray-50 rounded">
                  <TechStack skills={job.skills} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex justify-center gap-2 mt-6">
          <button
            disabled={params.page === 0}
            onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) - 1 }))}
            className="px-3 py-1 border rounded text-sm disabled:opacity-50"
          >
            Previous
          </button>
          <span className="px-3 py-1 text-sm text-gray-600">
            Page {(params.page ?? 0) + 1} of {totalPages}
          </span>
          <button
            disabled={(params.page ?? 0) >= totalPages - 1}
            onClick={() => setParams((p) => ({ ...p, page: (p.page ?? 0) + 1 }))}
            className="px-3 py-1 border rounded text-sm disabled:opacity-50"
          >
            Next
          </button>
        </div>
      )}
    </div>
  );
}
