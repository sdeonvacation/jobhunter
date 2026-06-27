import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import type { Contact, PeoplePage, PeopleStats, RelationshipStatus, Seniority } from '../types';
import { api } from '../api/client';

const STATUS_TABS: (RelationshipStatus | 'ALL')[] = [
  'ALL', 'DISCOVERED', 'CONTACTED', 'REPLIED', 'ENGAGED', 'REFERRED', 'GHOSTED',
];

const SORT_OPTIONS = [
  { value: 'priority', label: 'Priority' },
  { value: 'lastContact', label: 'Last Contact' },
  { value: 'name', label: 'Name' },
];

const PAGE_SIZE = 18;

const SENIORITY_COLORS: Record<Seniority, string> = {
  RECRUITER: 'bg-info/10 text-info border border-info/20',
  MANAGER: 'bg-accent/10 text-accent border border-accent/20',
  DIRECTOR: 'bg-warning/10 text-warning border border-warning/20',
  STAFF: 'bg-success/10 text-success border border-success/20',
  SENIOR: 'bg-purple-500/10 text-purple-400 border border-purple-500/20',
  IC: 'bg-surface-700 text-text-muted border border-surface-600',
};

function WarmthIndicator({ score }: { score: number }) {
  const bars = Math.min(5, Math.max(1, Math.round(score / 20)));
  return (
    <div className="flex gap-0.5 items-end h-4">
      {[1, 2, 3, 4, 5].map((i) => (
        <div
          key={i}
          className={`w-1 rounded-sm transition-colors ${
            i <= bars ? 'bg-accent' : 'bg-surface-600'
          }`}
          style={{ height: `${40 + i * 12}%` }}
        />
      ))}
    </div>
  );
}

function ContactCard({ contact, onClick }: { contact: Contact; onClick: () => void }) {
  return (
    <button
      onClick={onClick}
      className="w-full text-left bg-surface-800 border border-surface-700 rounded-lg p-4 hover:border-accent/40 hover:bg-surface-700/50 transition-all duration-150 animate-slide-up"
      style={{ animationFillMode: 'both' }}
    >
      <div className="flex items-start justify-between mb-2">
        <div className="min-w-0 flex-1">
          <h3 className="text-sm font-medium text-text-primary truncate">{contact.personName}</h3>
          {contact.title && (
            <p className="text-xs text-text-muted truncate mt-0.5">{contact.title}</p>
          )}
        </div>
        <span className="text-xs font-mono text-accent ml-2 shrink-0">
          {contact.contactPriorityScore.toFixed(0)}
        </span>
      </div>

      <p className="text-xs text-text-secondary truncate mb-3">{contact.companyName}</p>

      {contact.email && (
        <div className="flex items-center gap-1.5 mb-2">
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" className="text-text-muted shrink-0">
            <rect x="2" y="3" width="12" height="10" rx="1" />
            <path d="M2 5l6 4 6-4" />
          </svg>
          <span className="text-xs text-accent truncate">{contact.email}</span>
          <span className={`text-[9px] px-1 py-0.5 rounded ${
            contact.emailConfidence === 'HIGH' ? 'bg-success/10 text-success' :
            contact.emailConfidence === 'MEDIUM' ? 'bg-warning/10 text-warning' :
            'bg-surface-700 text-text-muted'
          }`}>
            {contact.emailConfidence}
          </span>
        </div>
      )}

      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          {contact.seniority && (
            <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${SENIORITY_COLORS[contact.seniority]}`}>
              {contact.seniority}
            </span>
          )}
          {contact.relationshipStatus && contact.relationshipStatus !== 'DISCOVERED' && (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-surface-700 text-text-muted">
              {contact.relationshipStatus}
            </span>
          )}
        </div>
        <WarmthIndicator score={contact.warmthScore} />
      </div>
    </button>
  );
}

function StatsBar({ stats }: { stats: PeopleStats | null }) {
  if (!stats) return null;

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
      <div className="bg-surface-800 border border-surface-700 rounded-lg p-3">
        <p className="text-xs text-text-muted uppercase tracking-wider">Total</p>
        <p className="text-xl font-bold text-text-primary mt-1">{stats.totalContacts}</p>
      </div>
      <div className="bg-surface-800 border border-surface-700 rounded-lg p-3">
        <p className="text-xs text-text-muted uppercase tracking-wider">Today</p>
        <p className="text-xl font-bold text-accent mt-1">{stats.discoveredToday}</p>
      </div>
      <div className="bg-surface-800 border border-surface-700 rounded-lg p-3">
        <p className="text-xs text-text-muted uppercase tracking-wider">Avg Priority</p>
        <p className="text-xl font-bold text-text-primary mt-1">{stats.avgPriorityScore.toFixed(1)}</p>
      </div>
      <div className="bg-surface-800 border border-surface-700 rounded-lg p-3">
        <p className="text-xs text-text-muted uppercase tracking-wider">Engaged</p>
        <p className="text-xl font-bold text-success mt-1">
          {(stats.byStatus?.ENGAGED ?? 0) + (stats.byStatus?.REFERRED ?? 0)}
        </p>
      </div>
    </div>
  );
}

export default function People() {
  const navigate = useNavigate();
  const [pageData, setPageData] = useState<PeoplePage | null>(null);
  const [stats, setStats] = useState<PeopleStats | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<RelationshipStatus | 'ALL'>('ALL');
  const [sort, setSort] = useState('priority');
  const [page, setPage] = useState(0);

  const loadPeople = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const status = statusFilter === 'ALL' ? undefined : statusFilter;
      const data = await api.people.list({ status, sort, page, size: PAGE_SIZE });
      setPageData(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load contacts');
    } finally {
      setLoading(false);
    }
  }, [statusFilter, sort, page]);

  const loadStats = useCallback(async () => {
    try {
      const data = await api.people.getStats();
      setStats(data);
    } catch {
      // stats are non-critical
    }
  }, []);

  useEffect(() => {
    loadPeople();
  }, [loadPeople]);

  useEffect(() => {
    loadStats();
  }, [loadStats]);

  function handleStatusChange(s: RelationshipStatus | 'ALL') {
    setStatusFilter(s);
    setPage(0);
  }

  const contacts = pageData?.content ?? [];
  const totalPages = pageData?.totalPages ?? 0;
  const totalElements = pageData?.totalElements ?? 0;

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-text-primary">People</h1>
        <select
          value={sort}
          onChange={(e) => { setSort(e.target.value); setPage(0); }}
          className="bg-surface-800 border border-surface-600 text-text-secondary rounded-md px-3 py-1.5 text-xs outline-none focus:border-accent"
        >
          {SORT_OPTIONS.map((opt) => (
            <option key={opt.value} value={opt.value}>{opt.label}</option>
          ))}
        </select>
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">
          {error}
        </div>
      )}

      <StatsBar stats={stats} />

      <div className="flex gap-2 flex-wrap mb-6">
        {STATUS_TABS.map((s) => (
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
            {stats && s !== 'ALL' && stats.byStatus?.[s] !== undefined && (
              <span className="ml-1 opacity-70">({stats.byStatus[s]})</span>
            )}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : contacts.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No contacts found</p>
          <p className="text-text-muted text-sm">Contacts will appear here as they are discovered from job postings.</p>
        </div>
      ) : (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {contacts.map((contact, i) => (
              <div key={contact.id} style={{ animationDelay: `${i * 30}ms` }}>
                <ContactCard
                  contact={contact}
                  onClick={() => navigate(`/people/${contact.id}`)}
                />
              </div>
            ))}
          </div>

          <div className="flex items-center justify-between mt-6 text-sm">
            <span className="text-text-muted">
              {totalElements} {totalElements === 1 ? 'contact' : 'contacts'}
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
