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
      <h1 className="text-2xl font-bold mb-4">Company Registry</h1>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded p-3 mb-4 text-sm">
          {error}
        </div>
      )}

      <div className="flex items-center gap-4 mb-4">
        <div className="flex gap-1">
          {STATUS_FILTERS.map((s) => (
            <button
              key={s}
              onClick={() => setStatusFilter(s)}
              className={`px-3 py-1 text-xs rounded ${
                statusFilter === s
                  ? 'bg-blue-600 text-white'
                  : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
              }`}
            >
              {s}
            </button>
          ))}
        </div>
        <select
          value={sortBy}
          onChange={(e) => setSortBy(e.target.value as 'priorityScore' | 'name')}
          className="text-sm border border-gray-300 rounded px-2 py-1"
        >
          <option value="priorityScore">Sort by Priority</option>
          <option value="name">Sort by Name</option>
        </select>
      </div>

      {loading ? (
        <div className="text-center py-8 text-gray-500">Loading...</div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-200 text-left text-gray-500">
                <th className="pb-2 font-medium">Name</th>
                <th className="pb-2 font-medium">Status</th>
                <th className="pb-2 font-medium">Country</th>
                <th className="pb-2 font-medium text-right">Priority</th>
                <th className="pb-2 font-medium text-right">Endpoints</th>
                <th className="pb-2 font-medium text-right">Interview Rate</th>
                <th className="pb-2 font-medium">Domain</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((company) => (
                <CompanyRow key={company.id} company={company} />
              ))}
            </tbody>
          </table>
          {sorted.length === 0 && (
            <p className="text-center py-8 text-gray-500">No companies found</p>
          )}
        </div>
      )}
    </div>
  );
}

function CompanyRow({ company }: { company: Company }) {
  const statusColor: Record<string, string> = {
    ACTIVE: 'bg-green-100 text-green-800',
    DISCOVERED: 'bg-blue-100 text-blue-800',
    PAUSED: 'bg-yellow-100 text-yellow-800',
    PROTECTED: 'bg-orange-100 text-orange-800',
    UNSUPPORTED: 'bg-red-100 text-red-800',
    PENDING_DETECTION: 'bg-gray-100 text-gray-800',
  };

  const endpointCount = company.endpointCount ?? company.careerEndpoints?.length ?? 0;

  return (
    <tr className="border-b border-gray-100 hover:bg-gray-50">
      <td className="py-2 font-medium">{company.name}</td>
      <td className="py-2">
        <span className={`text-xs px-2 py-0.5 rounded ${statusColor[company.status] || ''}`}>
          {company.status}
        </span>
      </td>
      <td className="py-2 text-xs text-gray-600">{company.country || '-'}</td>
      <td className="py-2 text-right font-mono">{company.priorityScore.toFixed(1)}</td>
      <td className="py-2 text-right">{endpointCount}</td>
      <td className="py-2 text-right">
        {company.totalApplications > 0
          ? `${(company.interviewRate * 100).toFixed(0)}%`
          : '-'}
      </td>
      <td className="py-2 text-xs text-gray-500">{company.domain || '-'}</td>
    </tr>
  );
}
