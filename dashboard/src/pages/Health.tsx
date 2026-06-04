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

interface HealthReport {
  totalEndpoints: number;
  errored: number;
  empty: number;
  neverCrawled: number;
  errors: EndpointHealth[];
  empties: EndpointHealth[];
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

  if (loading) return <div className="text-text-muted text-center py-12">Loading...</div>;
  if (!report) return <div className="text-danger text-center py-12">Failed to load health report</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Endpoint Health</h1>

      <div className="grid grid-cols-4 gap-4 mb-8">
        <StatCard label="Total Active" value={report.totalEndpoints} color="text-text-primary" />
        <StatCard label="Errored" value={report.errored} color="text-danger" />
        <StatCard label="Empty" value={report.empty} color="text-warning" />
        <StatCard label="Never Crawled" value={report.neverCrawled} color="text-text-muted" />
      </div>

      {report.errors.length > 0 && (
        <section className="mb-8">
          <h2 className="text-lg font-semibold text-danger mb-3">Errors ({report.errors.length})</h2>
          <div className="space-y-2">
            {report.errors.map((ep) => (
              <EndpointRow key={ep.id} endpoint={ep} />
            ))}
          </div>
        </section>
      )}

      {report.empties.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold text-warning mb-3">Empty ({report.empties.length})</h2>
          <div className="space-y-2">
            {report.empties.map((ep) => (
              <EndpointRow key={ep.id} endpoint={ep} />
            ))}
          </div>
        </section>
      )}

      {report.errors.length === 0 && report.empties.length === 0 && (
        <div className="text-center py-12">
          <p className="text-success text-lg mb-2">All endpoints healthy</p>
          <p className="text-text-muted text-sm">No errors or empty responses detected.</p>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-4">
      <p className="text-xs text-text-muted uppercase tracking-wider">{label}</p>
      <p className={`text-2xl font-bold mt-1 ${color}`}>{value}</p>
    </div>
  );
}

function EndpointRow({ endpoint }: { endpoint: EndpointHealth }) {
  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-4">
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
          <span className="text-xs bg-danger/20 text-danger px-2 py-1 rounded-full font-mono shrink-0 ml-3">
            {endpoint.consecutiveErrors}x failed
          </span>
        )}
      </div>
    </div>
  );
}
