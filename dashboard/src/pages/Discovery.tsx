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

  if (loading) return <div className="text-center py-8 text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Discovery</h1>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded p-3 mb-4 text-sm">
          {error}
        </div>
      )}

      {stats && (
        <div className="grid grid-cols-4 gap-4 mb-6">
          <StatsCard title="Discovered" value={stats.totalDiscovered} />
          <StatsCard title="Resolved" value={stats.totalResolved} />
          <StatsCard title="Active" value={stats.activeCompanies} />
          <StatsCard title="Pending" value={stats.pendingDetection} />
        </div>
      )}

      <section className="mb-6">
        <h2 className="text-lg font-semibold mb-3">Source Quality</h2>
        <div className="bg-white rounded border border-gray-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-50 border-b border-gray-200 text-left text-gray-500">
                <th className="px-4 py-2 font-medium">Source</th>
                <th className="px-4 py-2 font-medium text-right">Applications</th>
                <th className="px-4 py-2 font-medium text-right">Interviews</th>
                <th className="px-4 py-2 font-medium text-right">Interview Rate</th>
                <th className="px-4 py-2 font-medium">Rate</th>
              </tr>
            </thead>
            <tbody>
              {quality.map((sq) => (
                <tr key={sq.source} className="border-b border-gray-100">
                  <td className="px-4 py-2 font-medium">{sq.source}</td>
                  <td className="px-4 py-2 text-right">{sq.totalApplications}</td>
                  <td className="px-4 py-2 text-right">{sq.totalInterviews}</td>
                  <td className="px-4 py-2 text-right">{(sq.interviewRate * 100).toFixed(1)}%</td>
                  <td className="px-4 py-2">
                    <div className="w-24 bg-gray-200 rounded-full h-2">
                      <div
                        className="bg-blue-600 h-2 rounded-full"
                        style={{ width: `${Math.min(sq.interviewRate * 100, 100)}%` }}
                      />
                    </div>
                  </td>
                </tr>
              ))}
              {quality.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-4 text-center text-gray-500">
                    No source quality data yet
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="text-lg font-semibold mb-3">Recent Events</h2>
        <div className="bg-white rounded border border-gray-200 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-50 border-b border-gray-200 text-left text-gray-500">
                <th className="px-4 py-2 font-medium">Company</th>
                <th className="px-4 py-2 font-medium">Provider</th>
                <th className="px-4 py-2 font-medium">Source Job</th>
                <th className="px-4 py-2 font-medium">Outcome</th>
                <th className="px-4 py-2 font-medium">Date</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id} className="border-b border-gray-100">
                  <td className="px-4 py-2 font-medium">{event.companyName}</td>
                  <td className="px-4 py-2 text-gray-600">{event.provider}</td>
                  <td className="px-4 py-2 text-gray-600 truncate max-w-xs">
                    {event.sourceJobTitle || '-'}
                  </td>
                  <td className="px-4 py-2">
                    <OutcomeBadge outcome={event.outcome} />
                  </td>
                  <td className="px-4 py-2 text-gray-500">
                    {new Date(event.discoveredAt).toLocaleDateString()}
                  </td>
                </tr>
              ))}
              {events.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-4 text-center text-gray-500">
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
    REGISTERED: 'bg-green-100 text-green-800',
    ALREADY_EXISTS: 'bg-gray-100 text-gray-800',
    DETECTION_FAILED: 'bg-red-100 text-red-800',
    UNSUPPORTED_ATS: 'bg-yellow-100 text-yellow-800',
    NEW_ENDPOINT_ADDED: 'bg-blue-100 text-blue-800',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded ${colors[outcome] || 'bg-gray-100'}`}>
      {outcome.replace(/_/g, ' ')}
    </span>
  );
}
