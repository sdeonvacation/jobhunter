import { useState, useEffect, useCallback } from 'react';
import type { PatternAnalytics } from '../types/careerOps';
import { careerOps } from '../api/careerOps';

export default function Analytics() {
  const [data, setData] = useState<PatternAnalytics | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [since, setSince] = useState<string>('');

  const fetchPatterns = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await careerOps.getPatterns(since || undefined);
      setData(result);
    } catch (err) {
      setError('Failed to load analytics');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [since]);

  useEffect(() => {
    fetchPatterns();
  }, [fetchPatterns]);

  if (loading) {
    return <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>;
  }

  if (error) {
    return (
      <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 text-danger text-sm">
        {error}
      </div>
    );
  }

  if (!data) return null;

  const funnelStages = [
    { label: 'Scored', value: data.funnel.totalEvaluated },
    { label: 'Applied', value: data.funnel.applied },
    { label: 'Responded', value: data.funnel.responded },
    { label: 'Interviewing', value: data.funnel.interviewing },
    { label: 'Offered', value: data.funnel.offered },
    { label: 'Rejected', value: data.funnel.rejected },
  ];
  const maxFunnel = Math.max(...funnelStages.map(s => s.value), 1);

  const companyEntries = Object.entries(data.archetypeByCompany || {});
  const remoteEntries = Object.entries(data.archetypeByRemoteType || {});

  return (
    <div>
      {/* Header with date filter */}
      <div className="flex items-baseline justify-between mb-6">
        <h1 className="text-2xl font-bold text-text-primary">Analytics</h1>
        <div className="flex items-center gap-2">
          <label className="text-xs text-text-muted">Since:</label>
          <input
            type="date"
            value={since}
            onChange={(e) => setSince(e.target.value)}
            className="bg-surface-800 border border-surface-600 rounded-md px-2.5 py-1.5 text-sm text-text-secondary focus:border-accent focus:ring-2 focus:ring-accent/20 outline-none"
          />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Funnel */}
        <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up">
          <h2 className="text-sm font-medium text-text-primary mb-4">Application Funnel</h2>
          <div className="space-y-3">
            {funnelStages.map((stage, i) => (
              <div key={stage.label} className="animate-slide-up" style={{ animationDelay: `${i * 50}ms` }}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span className="text-text-secondary">{stage.label}</span>
                  <span className="font-mono text-text-primary">{stage.value}</span>
                </div>
                <div className="h-2 bg-surface-700 rounded-full overflow-hidden">
                  <div
                    className="h-full bg-accent rounded-full transition-all duration-500"
                    style={{ width: `${(stage.value / maxFunnel) * 100}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
          {/* Rates */}
          <div className="mt-4 pt-4 border-t border-surface-700 grid grid-cols-2 gap-3">
            <div className="text-center">
              <p className="text-lg font-mono text-text-primary">{(data.funnel.applicationRate * 100).toFixed(1)}%</p>
              <p className="text-[10px] text-text-muted uppercase">Apply rate</p>
            </div>
            <div className="text-center">
              <p className="text-lg font-mono text-text-primary">{(data.funnel.responseRate * 100).toFixed(1)}%</p>
              <p className="text-[10px] text-text-muted uppercase">Response rate</p>
            </div>
          </div>
        </div>

        {/* Score comparison */}
        <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up" style={{ animationDelay: '100ms' }}>
          <h2 className="text-sm font-medium text-text-primary mb-4">Score Comparison</h2>
          <div className="grid grid-cols-2 gap-4">
            <div className="text-center">
              <p className="text-2xl font-mono text-success">{data.scoreComparison.avgScorePositiveOutcome.toFixed(1)}</p>
              <p className="text-xs text-text-muted mt-1">Avg score (positive)</p>
              <p className="text-xs text-text-muted">{data.scoreComparison.positiveCount} jobs</p>
            </div>
            <div className="text-center">
              <p className="text-2xl font-mono text-danger">{data.scoreComparison.avgScoreNegativeOutcome.toFixed(1)}</p>
              <p className="text-xs text-text-muted mt-1">Avg score (negative)</p>
              <p className="text-xs text-text-muted">{data.scoreComparison.negativeCount} jobs</p>
            </div>
          </div>
          {data.scoreThreshold > 0 && (
            <div className="mt-4 pt-4 border-t border-surface-700">
              <p className="text-xs text-text-muted">Min score for positive outcomes:</p>
              <p className="text-xl font-mono text-accent">{data.scoreThreshold}</p>
            </div>
          )}
        </div>

        {/* Blockers */}
        <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up" style={{ animationDelay: '200ms' }}>
          <h2 className="text-sm font-medium text-text-primary mb-4">Blockers</h2>
          {data.blockerAnalysis.length === 0 ? (
            <p className="text-text-muted text-sm">No blockers identified yet</p>
          ) : (
            <div className="space-y-2">
              {data.blockerAnalysis.map((blocker, i) => (
                <div key={i} className="flex items-center justify-between">
                  <span className="text-text-secondary text-sm">{blocker.reason}</span>
                  <span className="text-xs font-mono text-text-muted bg-surface-700 px-2 py-0.5 rounded-full">
                    {blocker.count}×
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Tech stack gaps */}
        <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up" style={{ animationDelay: '300ms' }}>
          <h2 className="text-sm font-medium text-text-primary mb-4">Tech Stack Gaps</h2>
          {data.techStackGaps.length === 0 ? (
            <p className="text-text-muted text-sm">No gaps detected</p>
          ) : (
            <div className="space-y-2">
              {data.techStackGaps.map((gap, i) => (
                <div key={i} className="flex items-center justify-between">
                  <span className="text-text-secondary text-sm">{gap.skill}</span>
                  <span className="text-xs font-mono text-warning">{gap.count} jobs</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Archetype breakdown */}
      {(companyEntries.length > 0 || remoteEntries.length > 0) && (
        <div className="mt-6 grid grid-cols-1 lg:grid-cols-2 gap-6">
          {companyEntries.length > 0 && (
            <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up" style={{ animationDelay: '400ms' }}>
              <h2 className="text-sm font-medium text-text-primary mb-4">By Company</h2>
              <div className="space-y-2">
                {companyEntries.slice(0, 10).map(([company, count]) => (
                  <div key={company} className="flex items-center justify-between">
                    <span className="text-text-secondary text-sm truncate mr-2">{company}</span>
                    <span className="font-mono text-text-primary text-sm shrink-0">{count}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
          {remoteEntries.length > 0 && (
            <div className="bg-surface-800 rounded-xl border border-surface-700 p-5 animate-slide-up" style={{ animationDelay: '500ms' }}>
              <h2 className="text-sm font-medium text-text-primary mb-4">By Remote Type</h2>
              <div className="space-y-2">
                {remoteEntries.map(([type, count]) => (
                  <div key={type} className="flex items-center justify-between">
                    <span className="text-text-secondary text-sm">{type}</span>
                    <span className="font-mono text-text-primary text-sm">{count}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
