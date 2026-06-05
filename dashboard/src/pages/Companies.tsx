import { useState, useEffect, useCallback } from 'react';
import type { Company, CompanyStatus, PageResponse } from '../types';
import { api } from '../api/client';

const STATUS_FILTERS: (CompanyStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'DISCOVERED', 'PAUSED', 'PROTECTED', 'UNSUPPORTED'];
const PAGE_SIZE = 20;

function scoreToPriority(score: number): number {
  if (score <= 5) return Math.max(1, Math.round(score));
  // Legacy 0-100 scale mapping
  if (score <= 20) return 1;
  if (score <= 40) return 2;
  if (score <= 60) return 3;
  if (score <= 80) return 4;
  return 5;
}

export default function Companies() {
  const [pageData, setPageData] = useState<PageResponse<Company> | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<CompanyStatus | 'ALL'>('ALL');
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [page, setPage] = useState(0);

  // Debounce search input
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedSearch(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(timer);
  }, [search]);

  const loadCompanies = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const status = statusFilter === 'ALL' ? undefined : statusFilter;
      const data = await api.companies.list({
        status,
        search: debouncedSearch || undefined,
        page,
        size: PAGE_SIZE,
      });
      setPageData(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load companies');
    } finally {
      setLoading(false);
    }
  }, [statusFilter, debouncedSearch, page]);

  useEffect(() => {
    loadCompanies();
  }, [loadCompanies]);

  function handleStatusChange(s: CompanyStatus | 'ALL') {
    setStatusFilter(s);
    setPage(0);
  }

  const companies = pageData?.content ?? [];
  const totalPages = pageData?.totalPages ?? 0;
  const totalElements = pageData?.totalElements ?? 0;

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Company Registry</h1>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">
          {error}
        </div>
      )}

      <div className="flex items-center gap-4 mb-4">
        <div className="flex gap-2 flex-wrap">
          {STATUS_FILTERS.map((s) => (
            <button
              key={s}
              onClick={() => handleStatusChange(s)}
              className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                statusFilter === s
                  ? 'bg-accent text-white'
                  : 'bg-surface-700 text-text-secondary hover:bg-surface-600'
              }`}
            >
              {s}
            </button>
          ))}
        </div>
      </div>

      <div className="mb-6">
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search companies..."
          className="w-full max-w-sm bg-surface-800 border border-surface-600 text-text-primary rounded-md px-3 py-2 text-sm outline-none focus:border-accent placeholder:text-text-muted"
        />
      </div>

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : companies.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No companies found</p>
          <p className="text-text-muted text-sm">Companies will appear here as they are discovered.</p>
        </div>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left border-b border-surface-600">
                  <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Name</th>
                  <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Status</th>
                  <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Country</th>
                  <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Priority</th>
                  <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Endpoints</th>
                  <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Interview Rate</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-600">
                {companies.map((company) => (
                  <CompanyRow key={company.id} company={company} onPriorityChange={loadCompanies} />
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between mt-6 text-sm">
            <span className="text-text-muted">
              {totalElements} {totalElements === 1 ? 'company' : 'companies'}
            </span>
            <div className="flex items-center gap-3">
              <button
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0}
                className="rounded-md px-3 py-1.5 text-xs font-medium bg-surface-700 text-text-secondary hover:bg-surface-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                Previous
              </button>
              <span className="text-text-secondary">
                Page {page + 1} of {Math.max(1, totalPages)}
              </span>
              <button
                onClick={() => setPage((p) => p + 1)}
                disabled={page >= totalPages - 1}
                className="rounded-md px-3 py-1.5 text-xs font-medium bg-surface-700 text-text-secondary hover:bg-surface-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                Next
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function PriorityDots({ company, onPriorityChange }: { company: Company; onPriorityChange: () => void }) {
  const [priority, setPriority] = useState(() => scoreToPriority(company.priorityScore));
  const [saving, setSaving] = useState(false);

  async function handleClick(value: number) {
    const prev = priority;
    setPriority(value);
    setSaving(true);
    try {
      await api.companies.updatePriority(company.id, value);
      onPriorityChange();
    } catch {
      setPriority(prev);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={`flex gap-1 ${saving ? 'opacity-50' : ''}`}>
      {[1, 2, 3, 4, 5].map((v) => (
        <button
          key={v}
          onClick={() => handleClick(v)}
          disabled={saving}
          className="p-0 border-0 bg-transparent cursor-pointer disabled:cursor-not-allowed"
          aria-label={`Set priority ${v}`}
        >
          <span
            className={`inline-block w-3 h-3 rounded-full transition-colors ${
              v <= priority ? 'bg-accent' : 'bg-surface-600'
            }`}
          />
        </button>
      ))}
    </div>
  );
}

function CompanyRow({ company, onPriorityChange }: { company: Company; onPriorityChange: () => void }) {
  const statusColor: Record<string, string> = {
    ACTIVE: 'bg-success/10 text-success border border-success/20',
    DISCOVERED: 'bg-info/10 text-info border border-info/20',
    PAUSED: 'bg-warning/10 text-warning border border-warning/20',
    PROTECTED: 'bg-warning/10 text-warning border border-warning/20',
    UNSUPPORTED: 'bg-danger/10 text-danger border border-danger/20',
    PENDING_DETECTION: 'bg-surface-700 text-text-muted border border-surface-600',
  };

  const endpointCount = company.endpointCount ?? company.careerEndpoints?.length ?? 0;
  const interviewPct = company.totalApplications > 0
    ? company.interviewRate * 100
    : 0;

  return (
    <tr className="hover:bg-surface-700/50 transition-colors">
      <td className="py-3 text-sm text-text-primary font-medium">{company.name}</td>
      <td className="py-3">
        <span className={`text-xs px-2 py-0.5 rounded-md font-medium ${statusColor[company.status] || 'bg-surface-700 text-text-muted'}`}>
          {company.status}
        </span>
      </td>
      <td className="py-3 text-sm text-text-secondary">{company.country || '-'}</td>
      <td className="py-3">
        <PriorityDots company={company} onPriorityChange={onPriorityChange} />
      </td>
      <td className="py-3 text-right text-sm text-text-primary font-mono">{endpointCount}</td>
      <td className="py-3 text-right">
        <div className="flex items-center justify-end gap-2">
          <div className="w-16 bg-surface-700 rounded-full h-1.5">
            <div
              className="bg-accent rounded-full h-1.5"
              style={{ width: `${Math.min(interviewPct, 100)}%` }}
            />
          </div>
          <span className="font-mono text-xs text-text-secondary w-8 text-right">
            {company.totalApplications > 0 ? `${interviewPct.toFixed(0)}%` : '-'}
          </span>
        </div>
      </td>
    </tr>
  );
}
