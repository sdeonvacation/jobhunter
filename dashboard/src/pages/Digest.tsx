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
        // No digest available yet - not an error
        setDigest(null);
      } else {
        setError(err instanceof Error ? err.message : 'Failed to load digest');
      }
    } finally {
      setLoading(false);
    }
  }

  if (loading) return <div className="text-center py-8 text-gray-500">Loading...</div>;

  if (!digest) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-4">Daily Digest</h1>
        {error ? (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded p-3 text-sm">{error}</div>
        ) : (
          <p className="text-gray-500">No digest available yet</p>
        )}
      </div>
    );
  }

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Daily Digest - {digest.date}</h1>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded p-3 mb-4 text-sm">{error}</div>
      )}

      {/* Top Opportunity */}
      {digest.topOpportunityTitle && (
        <div className="bg-gradient-to-r from-blue-50 to-purple-50 border border-blue-200 rounded-lg p-4 mb-6">
          <p className="text-xs text-blue-600 font-medium uppercase">Top Opportunity</p>
          <p className="text-lg font-semibold mt-1">{digest.topOpportunityTitle}</p>
          <div className="flex items-center gap-3 mt-2">
            {digest.topOpportunityCompany && (
              <span className="text-sm text-gray-600">{digest.topOpportunityCompany}</span>
            )}
            <ScoreBadge score={digest.topOpportunityScore} label="Score" />
          </div>
        </div>
      )}

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4 mb-6">
        <StatsCard title="New Jobs" value={digest.newJobsCount} />
        <StatsCard title="Skipped" value={digest.skippedCount} />
        <StatsCard
          title="Top Score"
          value={digest.topOpportunityScore}
          trend={digest.topOpportunityScore >= 70 ? 'up' : 'neutral'}
        />
      </div>

      {/* Company Trends */}
      <div className="grid grid-cols-2 gap-4 mb-6">
        <section className="bg-white rounded-lg border border-gray-200 p-4">
          <h3 className="text-sm font-semibold text-green-700 mb-2">🔥 Heating Companies</h3>
          {digest.heatingCompanies.length > 0 ? (
            <ul className="space-y-1">
              {digest.heatingCompanies.map((c) => (
                <li key={c} className="text-sm text-gray-700">{c}</li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-gray-400">None</p>
          )}
        </section>

        <section className="bg-white rounded-lg border border-gray-200 p-4">
          <h3 className="text-sm font-semibold text-blue-700 mb-2">❄️ Cooling Companies</h3>
          {digest.coolingCompanies.length > 0 ? (
            <ul className="space-y-1">
              {digest.coolingCompanies.map((c) => (
                <li key={c} className="text-sm text-gray-700">{c}</li>
              ))}
            </ul>
          ) : (
            <p className="text-sm text-gray-400">None</p>
          )}
        </section>
      </div>

      {/* Source Interview Rates */}
      <section className="bg-white rounded-lg border border-gray-200 p-4">
        <h3 className="text-sm font-semibold text-gray-700 mb-3">Source Interview Rates</h3>
        {Object.keys(digest.sourceInterviewRates).length > 0 ? (
          <div className="space-y-2">
            {Object.entries(digest.sourceInterviewRates).map(([source, rate]) => (
              <div key={source} className="flex items-center gap-3">
                <span className="text-sm w-32 text-gray-600">{source}</span>
                <div className="flex-1 bg-gray-200 rounded-full h-2">
                  <div
                    className="bg-blue-600 h-2 rounded-full"
                    style={{ width: `${Math.min(rate * 100, 100)}%` }}
                  />
                </div>
                <span className="text-xs text-gray-500 w-12 text-right">
                  {(rate * 100).toFixed(0)}%
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-gray-400">No data yet</p>
        )}
      </section>
    </div>
  );
}
