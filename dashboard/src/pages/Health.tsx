import { useState, useEffect } from 'react';
import { fetchApi } from '../api/client';

interface EndpointHealth {
  id: string;
  companyName: string;
  atsType: string;
  atsSlug?: string;
  url: string;
  status: string;
  errorMessage?: string;
  consecutiveErrors: number;
  lastCrawledAt?: string;
}

interface AggregatorHealth {
  name: string;
  status: string;
  jobsFetched: number;
  errors: number;
  errorMessage?: string;
  lastRunAt?: string;
  elapsedMs: number;
}

interface HealthReport {
  totalEndpoints: number;
  errored: number;
  empty: number;
  neverCrawled: number;
  errors: EndpointHealth[];
  empties: EndpointHealth[];
  aggregatorIssues: AggregatorHealth[];
}

export default function Health() {
  const [report, setReport] = useState<HealthReport | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchApi<HealthReport>('/api/admin/health')
      .then(setReport)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>;
  if (!report) return <div className="text-danger text-center py-12">Failed to load health report</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Endpoint Health</h1>

      <div className="grid grid-cols-4 gap-4 mb-8">
        <StatCard label="Total Active" value={report.totalEndpoints} color="text-text-primary" borderColor="border-t-success" />
        <StatCard label="Errored" value={report.errored} color="text-danger" borderColor="border-t-danger" pulse={report.errored > 0} />
        <StatCard label="Empty" value={report.empty} color="text-warning" borderColor="border-t-warning" />
        <StatCard label="Never Crawled" value={report.neverCrawled} color="text-text-muted" borderColor="border-t-surface-500" />
      </div>

      {report.errors.length > 0 && (
        <section className="mb-8 animate-fade-in">
          <h2 className="text-lg font-semibold text-danger mb-3">Errors ({report.errors.length})</h2>
          <div className="space-y-2">
            {report.errors.map((ep) => (
              <EndpointRow key={ep.id} endpoint={ep} />
            ))}
          </div>
        </section>
      )}

      {report.empties.length > 0 && (
        <section className="animate-fade-in">
          <h2 className="text-lg font-semibold text-warning mb-3">Empty ({report.empties.length})</h2>
          <div className="space-y-2">
            {report.empties.map((ep) => (
              <EndpointRow key={ep.id} endpoint={ep} />
            ))}
          </div>
        </section>
      )}

      {report.aggregatorIssues.length > 0 && (
        <section className="mb-8 animate-fade-in">
          <h2 className="text-lg font-semibold text-warning mb-3">Aggregator Issues ({report.aggregatorIssues.length})</h2>
          <div className="space-y-2">
            {report.aggregatorIssues.map((agg) => (
              <AggregatorRow key={agg.name} aggregator={agg} />
            ))}
          </div>
        </section>
      )}

      {report.errors.length === 0 && report.empties.length === 0 && report.aggregatorIssues.length === 0 && (
        <div className="text-center py-12 animate-fade-in">
          <p className="text-success text-lg mb-2">All endpoints healthy</p>
          <p className="text-text-muted text-sm">No errors or empty responses detected.</p>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, value, color, borderColor, pulse }: { label: string; value: number; color: string; borderColor: string; pulse?: boolean }) {
  return (
    <div className={`bg-surface-800 border border-surface-600 border-t-2 ${borderColor} rounded-lg p-4 animate-fade-in ${pulse ? 'animate-pulse-glow' : ''}`}>
      <p className="text-xs text-text-muted uppercase tracking-wider">{label}</p>
      <p className={`text-2xl font-bold mt-1 font-mono ${color}`}>{value}</p>
    </div>
  );
}

function EndpointRow({ endpoint }: { endpoint: EndpointHealth }) {
  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-4 transition-all duration-150 hover:border-surface-500">
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-text-primary">{endpoint.companyName}</span>
            <span className="text-xs bg-surface-700 text-text-muted px-2 py-0.5 rounded font-mono">
              {endpoint.atsType}
            </span>
            {endpoint.atsSlug && (
              <span className="text-xs text-text-muted font-mono">/{endpoint.atsSlug}</span>
            )}
          </div>
          {endpoint.errorMessage && (
            <p className="text-xs text-danger mt-1 font-mono truncate">{endpoint.errorMessage}</p>
          )}
          <div className="flex items-center gap-3 mt-1">
            <a
              href={endpoint.url}
              target="_blank"
              rel="noopener noreferrer"
              className="text-xs text-accent hover:underline truncate max-w-md"
            >
              {endpoint.url}
            </a>
            {endpoint.lastCrawledAt && (
              <span className="text-xs text-text-muted shrink-0">
                Last crawled: {new Date(endpoint.lastCrawledAt).toLocaleDateString()}
              </span>
            )}
          </div>
        </div>
        {endpoint.consecutiveErrors > 0 && (
          <span className="text-xs bg-danger/20 text-danger px-2 py-1 rounded-full font-mono shrink-0 ml-3 animate-pulse-soft">
            {endpoint.consecutiveErrors}x failed
          </span>
        )}
      </div>
    </div>
  );
}

function AggregatorRow({ aggregator }: { aggregator: AggregatorHealth }) {
  const isError = aggregator.status === 'ERROR';
  const statusColor = isError ? 'text-danger' : 'text-warning';
  const badgeBg = isError ? 'bg-danger/20 text-danger' : 'bg-warning/20 text-warning';

  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-4 transition-all duration-150 hover:border-surface-500">
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-text-primary">{aggregator.name}</span>
            <span className={`text-xs px-2 py-0.5 rounded font-mono ${badgeBg}`}>
              {aggregator.status}
            </span>
          </div>
          {aggregator.errorMessage && (
            <p className="text-xs text-danger mt-1 font-mono truncate">{aggregator.errorMessage}</p>
          )}
          <div className="flex items-center gap-3 mt-1">
            <span className="text-xs text-text-muted">
              Fetched: <span className={`font-mono ${statusColor}`}>{aggregator.jobsFetched}</span>
            </span>
            {aggregator.errors > 0 && (
              <span className="text-xs text-danger font-mono">
                {aggregator.errors} error{aggregator.errors > 1 ? 's' : ''}
              </span>
            )}
            <span className="text-xs text-text-muted font-mono">
              {aggregator.elapsedMs}ms
            </span>
            {aggregator.lastRunAt && (
              <span className="text-xs text-text-muted shrink-0">
                Last run: {new Date(aggregator.lastRunAt).toLocaleDateString()}
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
