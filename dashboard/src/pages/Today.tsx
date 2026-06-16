import { useState, useEffect, useCallback } from 'react';
import type { ScoredAction, ActionType } from '../types';
import { api } from '../api/client';

const ACTION_ICONS: Record<ActionType, JSX.Element> = {
  FOLLOW_UP: (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 10h12M12 6l4 4-4 4" />
    </svg>
  ),
  CONNECT: (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="7" cy="8" r="3" />
      <circle cx="13" cy="8" r="3" />
      <path d="M3 17v-1a4 4 0 014-4h6a4 4 0 014 4v1" />
    </svg>
  ),
  APPLY: (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="3" width="12" height="14" rx="1" />
      <path d="M7 7h6M7 10h6M7 13h4" />
    </svg>
  ),
  PREPARE: (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10 3v14M3 10h14" />
      <circle cx="10" cy="10" r="7" />
    </svg>
  ),
  SEND_MESSAGE: (
    <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
      <path d="M4 4h12v10H7l-3 3V4z" />
    </svg>
  ),
};

function urgencyColor(score: number): string {
  if (score >= 0.8) return 'bg-red-500';
  if (score >= 0.5) return 'bg-yellow-500';
  if (score >= 0.3) return 'bg-green-500';
  return 'bg-surface-500';
}

function urgencyLabel(score: number): string {
  if (score >= 0.8) return 'Critical';
  if (score >= 0.5) return 'High';
  if (score >= 0.3) return 'Medium';
  return 'Low';
}

function ActionCard({ action, index }: { action: ScoredAction; index: number }) {
  return (
    <div
      className="bg-surface-800 border border-surface-600 rounded-lg p-5 animate-slide-up"
      style={{ animationDelay: `${index * 60}ms`, animationFillMode: 'both' }}
    >
      <div className="flex items-start gap-4">
        <div className="shrink-0 p-2 bg-surface-700 rounded-lg text-accent">
          {ACTION_ICONS[action.type]}
        </div>

        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-xs font-medium uppercase tracking-wider text-text-muted">
              {action.type.replace('_', ' ')}
            </span>
            <span className={`inline-block w-2 h-2 rounded-full ${urgencyColor(action.urgencyScore)}`} title={urgencyLabel(action.urgencyScore)} />
          </div>

          <h3 className="text-sm font-semibold text-text-primary truncate">
            {action.contactName || action.jobTitle || action.companyName}
          </h3>
          <p className="text-xs text-text-secondary mt-0.5">
            {action.companyName}
            {action.jobTitle && action.contactName ? ` · ${action.jobTitle}` : ''}
          </p>

          <p className="text-xs text-text-muted mt-2 line-clamp-2">
            {action.reason}
          </p>
        </div>

        <div className="shrink-0 flex flex-col items-end gap-1.5">
          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-accent/15 text-accent">
            {Math.round(action.impactScore * 100)}
          </span>
          <span className="text-[10px] text-text-muted whitespace-nowrap">
            {action.expiresIn}
          </span>
        </div>
      </div>
    </div>
  );
}

export default function Today() {
  const [actions, setActions] = useState<ScoredAction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchActions = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.actions.getToday(10);
      setActions(data.sort((a, b) => b.actionScore - a.actionScore));
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load actions');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchActions();
  }, [fetchActions]);

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-text-primary">Today</h1>
        <p className="text-sm text-text-muted mt-1">
          Priority actions sorted by impact
        </p>
      </div>

      {loading && (
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="bg-surface-800 border border-surface-600 rounded-lg p-5">
              <div className="flex items-start gap-4">
                <div className="skeleton w-9 h-9 rounded-lg" />
                <div className="flex-1">
                  <div className="skeleton h-3 w-20 mb-2" />
                  <div className="skeleton h-4 w-48 mb-1.5" />
                  <div className="skeleton h-3 w-32" />
                </div>
                <div className="skeleton h-5 w-10 rounded-full" />
              </div>
            </div>
          ))}
        </div>
      )}

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 rounded-lg p-4 text-sm text-red-400">
          {error}
        </div>
      )}

      {!loading && !error && actions.length === 0 && (
        <div className="text-center py-16">
          <div className="text-4xl mb-3">✓</div>
          <p className="text-text-secondary text-lg font-medium">Nothing urgent today</p>
          <p className="text-text-muted text-sm mt-1">All caught up. Check back tomorrow.</p>
        </div>
      )}

      {!loading && !error && actions.length > 0 && (
        <div className="space-y-3">
          {actions.map((action, i) => (
            <ActionCard key={action.entityId} action={action} index={i} />
          ))}
        </div>
      )}
    </div>
  );
}
