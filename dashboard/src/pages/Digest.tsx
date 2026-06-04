import { useState, useEffect } from 'react';
import type { DailyDigest } from '../types';
import { api, ApiError } from '../api/client';
import StatsCard from '../components/StatsCard';
import ScoreBadge from '../components/ScoreBadge';

export default function Digest() {
  const [digest, setDigest] = useState<DailyDigest | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadDigest();
  }, []);

  async function loadDigest() {
    setLoading(true);
    setError(null);
    try {
      const data = await api.jobs.getDailyDigest();
      setDigest(data);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        setDigest(null);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load digest');
      }
    } finally {
      setLoading(false);
    }
  }

  if (loading) return <div className="text-text-muted text-center py-12">Loading...</div>;

  if (!digest) {
    return (
      <div>
        <h1 className="text-2xl font-bold text-text-primary mb-6">Daily Digest</h1>
        {error ? (
          <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm">{error}</div>
        ) : (
          <div className="text-center py-12">
            <p className="text-text-muted text-lg mb-2">No digest available yet</p>
            <p className="text-text-muted text-sm">The daily digest will appear here after the first scheduled scan completes.</p>
          </div>
        )}
      </div>
    );
  }

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">
        Daily Digest
        <span className="ml-3 text-sm font-mono text-text-muted font-normal">{digest.date}</span>
      </h1>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">{error}</div>
      )}

      {/* Top Opportunity */}
      {digest.topOpportunityTitle && (
        <div className="bg-surface-800 border border-accent/30 rounded-lg p-5 mb-6">
          <p className="text-xs text-accent font-medium uppercase tracking-wider mb-2">Top Opportunity</p>
          <p className="text-lg font-semibold text-text-primary">{digest.topOpportunityTitle}</p>
          <div className="flex items-center gap-3 mt-2">
            {digest.topOpportunityCompany && (
              <span className="text-sm text-text-secondary">{digest.topOpportunityCompany}</span>
            )}
            <ScoreBadge score={digest.topOpportunityScore} label="Score" />
          </div>
        </div>
      )}

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4 mb-8">
        <StatsCard title="New Jobs" value={digest.newJobsCount} />
        <StatsCard title="Skipped" value={digest.skippedCount} />
        <StatsCard
          title="Top Score"
          value={digest.topOpportunityScore}
          trend={digest.topOpportunityScore >= 70 ? 'up' : 'neutral'}
        />
      </div>

      {/* Company Trends */}
      <div className="grid grid-cols-2 gap-4 mb-8">
        <section className="bg-surface-800 border border-surface-600 rounded-lg p-4">
          <h3 className="text-sm font-semibold text-success mb-3 flex items-center gap-2">
            <span className="text-success">&#9650;</span>
            Heating Companies
          </h3>
          {digest.heatingCompanies.length > 0 ? (
            <ul className="space-y-1.5">
              {digest.heatingCompanies.map((c) => (
                <li key={c} className="text-sm text-text-primary flex items-center gap-2">
                  <span className="w-1.5 h-1.5 rounded-full bg-success shrink-0"></span>
                  {c}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-text-muted">None</p>
          )}
        </section>

        <section className="bg-surface-800 border border-surface-600 rounded-lg p-4">
          <h3 className="text-sm font-semibold text-danger mb-3 flex items-center gap-2">
            <span className="text-danger">&#9660;</span>
            Cooling Companies
          </h3>
          {digest.coolingCompanies.length > 0 ? (
            <ul className="space-y-1.5">
              {digest.coolingCompanies.map((c) => (
                <li key={c} className="text-sm text-text-primary flex items-center gap-2">
                  <span className="w-1.5 h-1.5 rounded-full bg-danger shrink-0"></span>
                  {c}
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-text-muted">None</p>
          )}
        </section>
      </div>

      {/* Source Interview Rates */}
      <section className="bg-surface-800 border border-surface-600 rounded-lg p-4">
        <h3 className="text-sm font-semibold text-text-primary mb-4">Source Interview Rates</h3>
        {Object.keys(digest.sourceInterviewRates).length > 0 ? (
          <div className="space-y-3">
            {Object.entries(digest.sourceInterviewRates).map(([source, rate]) => (
              <div key={source} className="flex items-center gap-3">
                <span className="text-sm w-32 text-text-secondary truncate">{source}</span>
                <div className="flex-1 bg-surface-700 rounded-full h-1.5">
                  <div
                    className="bg-accent rounded-full h-1.5"
                    style={{ width: `${Math.min(rate * 100, 100)}%` }}
                  />
                </div>
                <span className="text-xs text-text-muted font-mono w-10 text-right">
                  {(rate * 100).toFixed(0)}%
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-text-muted">No data yet</p>
        )}
      </section>
    </div>
  );
}
