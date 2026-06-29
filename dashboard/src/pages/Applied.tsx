import { useState, useEffect, useCallback, useRef } from 'react';
import type { Job } from '../types';
import { api } from '../api/client';
import JobCard from '../components/JobCard';

const SORT_OPTIONS = [
  { value: 'appliedAt',   label: 'Applied date' },
  { value: 'matchScore',  label: 'Match score' },
  { value: 'opportunity', label: 'Opportunity' },
  { value: 'company',     label: 'Company' },
  { value: 'title',       label: 'Title' },
  { value: 'date',        label: 'Posted date' },
];

export default function Applied() {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [loading, setLoading] = useState(false);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('appliedAt');
  const [search, setSearch] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchApplied = useCallback(async () => {
    setLoading(true);
    try {
      const result = await api.jobs.getApplied(page, 20, sort, search);
      setJobs(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (err) {
      console.error('Failed to fetch applied jobs', err);
    } finally {
      setLoading(false);
    }
  }, [page, sort, search]);

  useEffect(() => {
    fetchApplied();
  }, [fetchApplied]);

  // Debounce search input → committed search state
  const handleSearchChange = (value: string) => {
    setSearchInput(value);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setSearch(value);
      setPage(0);
    }, 300);
  };

  const handleSortChange = (value: string) => {
    setSort(value);
    setPage(0);
  };

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
      {/* Header */}
      <div className="flex items-baseline gap-3 mb-5">
        <h1 className="text-2xl font-bold text-text-primary">Applied</h1>
        {totalElements > 0 && (
          <span className="text-sm font-mono text-text-muted bg-surface-700 px-2 py-0.5 rounded-full">
            {totalElements}
          </span>
        )}
      </div>

      {/* Controls */}
      <div className="flex flex-col sm:flex-row gap-2 mb-5">
        {/* Search */}
        <div className="relative flex-1">
          <svg
            className="absolute left-2.5 top-1/2 -translate-y-1/2 text-text-muted w-4 h-4 pointer-events-none"
            fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
          >
            <circle cx="11" cy="11" r="8" />
            <path d="M21 21l-4.35-4.35" strokeLinecap="round" />
          </svg>
          <input
            type="text"
            placeholder="Search title or company..."
            value={searchInput}
            onChange={(e) => handleSearchChange(e.target.value)}
            className="w-full bg-surface-800 border border-surface-600 rounded-md pl-8 pr-3 py-1.5 text-sm text-text-primary placeholder:text-text-muted focus:outline-none focus:border-accent transition-colors"
          />
          {searchInput && (
            <button
              onClick={() => { setSearchInput(''); setSearch(''); setPage(0); }}
              className="absolute right-2.5 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-secondary"
            >
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path d="M6 18L18 6M6 6l12 12" strokeLinecap="round" />
              </svg>
            </button>
          )}
        </div>

        {/* Sort */}
        <div className="flex items-center gap-2 shrink-0">
          <span className="text-xs text-text-muted whitespace-nowrap">Sort by</span>
          <select
            value={sort}
            onChange={(e) => handleSortChange(e.target.value)}
            className="bg-surface-800 border border-surface-600 rounded-md px-2.5 py-1.5 text-sm text-text-secondary focus:outline-none focus:border-accent transition-colors cursor-pointer"
          >
            {SORT_OPTIONS.map((o) => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Results */}
      {loading ? (
        <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>
      ) : jobs.length === 0 ? (
        <div className="text-center py-16 animate-fade-in">
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            {search ? (
              <svg width="36" height="36" viewBox="0 0 36 36" fill="none" className="text-text-muted">
                <circle cx="16" cy="16" r="10" stroke="currentColor" strokeWidth="1.5" opacity="0.4" />
                <path d="M24 24l6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" opacity="0.4" />
              </svg>
            ) : (
              <svg width="40" height="40" viewBox="0 0 40 40" fill="none" className="text-accent">
                <circle cx="20" cy="20" r="16" stroke="currentColor" strokeWidth="1.5" opacity="0.3" />
                <path d="M13 20l5 5 9-9" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            )}
          </div>
          {search ? (
            <>
              <p className="text-text-secondary text-lg mb-2 font-medium">No results for "{search}"</p>
              <button
                onClick={() => { setSearchInput(''); setSearch(''); setPage(0); }}
                className="text-accent text-sm hover:underline mt-1"
              >
                Clear search
              </button>
            </>
          ) : (
            <>
              <p className="text-text-secondary text-lg mb-2 font-medium">No applications yet</p>
              <p className="text-text-muted text-sm max-w-xs mx-auto">
                When you find a great match, mark it as applied from the Daily Digest or Jobs page. Your applications will appear here.
              </p>
            </>
          )}
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
