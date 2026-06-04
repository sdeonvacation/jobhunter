import { useState, useEffect } from 'react';
import type { Company, CompanyStatus } from '../types';
import { api } from '../api/client';

const STATUS_FILTERS: (CompanyStatus | 'ALL')[] = ['ALL', 'ACTIVE', 'DISCOVERED', 'PAUSED', 'PROTECTED', 'UNSUPPORTED'];

export default function Companies() {
  const [companies, setCompanies] = useState<Company[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<CompanyStatus | 'ALL'>('ALL');
  const [sortBy, setSortBy] = useState<'priorityScore' | 'name'>('priorityScore');

  useEffect(() => {
    loadCompanies();
  }, [statusFilter]);

  async function loadCompanies() {
    setLoading(true);
    setError(null);
    try {
      const status = statusFilter === 'ALL' ? undefined : statusFilter;
      const data = await api.companies.list(status);
      setCompanies(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load companies');
    } finally {
      setLoading(false);
    }
  }

  const sorted = [...companies].sort((a, b) => {
    if (sortBy === 'priorityScore') return b.priorityScore - a.priorityScore;
    return a.name.localeCompare(b.name);
  });

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Company Registry</h1>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">
          {error}
        </div>
      )}

      <div className="flex items-center gap-4 mb-6">
        <div className="flex gap-2">
          {STATUS_FILTERS.map((s) => (
            <button
              key={s}
              onClick={() => setStatusFilter(s)}
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
        <select
          value={sortBy}
          onChange={(e) => setSortBy(e.target.value as 'priorityScore' | 'name')}
          className="bg-surface-800 border border-surface-600 text-text-primary rounded-md px-3 py-1.5 text-sm outline-none focus:border-accent"
        >
          <option value="priorityScore">Sort by Priority</option>
          <option value="name">Sort by Name</option>
        </select>
      </div>

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : sorted.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No companies found</p>
          <p className="text-text-muted text-sm">Companies will appear here as they are discovered.</p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b border-surface-600">
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Name</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Status</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Country</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Priority</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Endpoints</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Interview Rate</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-600">
              {sorted.map((company) => (
                <CompanyRow key={company.id} company={company} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function CompanyRow({ company }: { company: Company }) {
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
      <td className="py-3 text-right">
        <span className={`font-mono text-sm ${company.priorityScore > 70 ? 'text-accent' : 'text-text-primary'}`}>
          {company.priorityScore.toFixed(1)}
        </span>
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
