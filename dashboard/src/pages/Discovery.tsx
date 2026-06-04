import { useState, useEffect } from 'react';
import type { DiscoveryStats, SourceQuality, DiscoveryEvent } from '../types';
import { api } from '../api/client';
import StatsCard from '../components/StatsCard';

export default function Discovery() {
  const [stats, setStats] = useState<DiscoveryStats | null>(null);
  const [quality, setQuality] = useState<SourceQuality[]>([]);
  const [events, setEvents] = useState<DiscoveryEvent[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadData();
  }, []);

  async function loadData() {
    setLoading(true);
    setError(null);
    try {
      const [statsData, qualityData, eventsData] = await Promise.all([
        api.discovery.getStats(),
        api.discovery.getSourceQuality(),
        api.discovery.getEvents(0, 20),
      ]);
      setStats(statsData);
      setQuality(qualityData);
      setEvents(eventsData.content);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load discovery data');
    } finally {
      setLoading(false);
    }
  }

  if (loading) return <div className="text-text-muted text-center py-12">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Discovery</h1>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">
          {error}
        </div>
      )}

      {stats && (
        <div className="grid grid-cols-4 gap-4 mb-8">
          <StatsCard title="Discovered" value={stats.totalDiscovered} />
          <StatsCard title="Resolved" value={stats.totalResolved} />
          <StatsCard title="Active" value={stats.activeCompanies} />
          <StatsCard title="Pending" value={stats.pendingDetection} />
        </div>
      )}

      <section className="mb-8">
        <h2 className="text-lg font-semibold text-text-primary mb-4">Source Quality</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b border-surface-600">
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Source</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Applications</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Interviews</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium text-right">Rate</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium w-32"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-600">
              {quality.map((sq) => (
                <tr key={sq.source} className="hover:bg-surface-700/50 transition-colors">
                  <td className="py-3 text-text-primary font-medium">{sq.source}</td>
                  <td className="py-3 text-right text-text-secondary font-mono">{sq.totalApplications}</td>
                  <td className="py-3 text-right text-text-secondary font-mono">{sq.totalInterviews}</td>
                  <td className="py-3 text-right text-text-primary font-mono">{(sq.interviewRate * 100).toFixed(1)}%</td>
                  <td className="py-3">
                    <div className="bg-surface-700 rounded-full h-1.5">
                      <div
                        className="bg-accent rounded-full h-1.5"
                        style={{ width: `${Math.min(sq.interviewRate * 100, 100)}%` }}
                      />
                    </div>
                  </td>
                </tr>
              ))}
              {quality.length === 0 && (
                <tr>
                  <td colSpan={5} className="py-8 text-center text-text-muted">
                    No source quality data yet
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold text-text-primary mb-4">Recent Events</h2>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left border-b border-surface-600">
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Company</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Provider</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Source Job</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Outcome</th>
                <th className="pb-3 text-text-muted text-xs uppercase tracking-wider font-medium">Date</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-surface-600">
              {events.map((event) => (
                <tr key={event.id} className="hover:bg-surface-700/50 transition-colors">
                  <td className="py-3 text-text-primary font-medium">{event.companyName}</td>
                  <td className="py-3 text-text-secondary">{event.provider}</td>
                  <td className="py-3 text-text-secondary truncate max-w-xs">
                    {event.sourceJobTitle || '-'}
                  </td>
                  <td className="py-3">
                    <OutcomeBadge outcome={event.outcome} />
                  </td>
                  <td className="py-3 text-text-muted font-mono text-xs">
                    {new Date(event.discoveredAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
              {events.length === 0 && (
                <tr>
                  <td colSpan={5} className="py-8 text-center text-text-muted">
                    No discovery events yet
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function OutcomeBadge({ outcome }: { outcome: string }) {
  const colors: Record<string, string> = {
    REGISTERED: 'bg-success/10 text-success border border-success/20',
    ALREADY_EXISTS: 'bg-surface-700 text-text-muted border border-surface-600',
    DETECTION_FAILED: 'bg-danger/10 text-danger border border-danger/20',
    UNSUPPORTED_ATS: 'bg-warning/10 text-warning border border-warning/20',
    NEW_ENDPOINT_ADDED: 'bg-info/10 text-info border border-info/20',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded-md font-medium ${colors[outcome] || 'bg-surface-700 text-text-muted'}`}>
      {outcome.replace(/_/g, ' ')}
    </span>
  );
}
