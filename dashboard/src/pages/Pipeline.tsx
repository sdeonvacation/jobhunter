import { useState, useEffect } from 'react';
import type { Application, ApplicationStatus } from '../types';
import { api } from '../api/client';

const STATUSES: (ApplicationStatus | 'ALL')[] = [
  'ALL',
  'APPLIED',
  'PHONE_SCREEN',
  'INTERVIEWING',
  'OFFERED',
  'REJECTED',
];

const stageLabels: Record<ApplicationStatus, string> = {
  INTERESTED: 'Interested',
  APPLIED: 'Applied',
  PHONE_SCREEN: 'Screening',
  INTERVIEWING: 'Interview',
  OFFERED: 'Offer',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
};

const statusColors: Record<ApplicationStatus, string> = {
  INTERESTED: 'bg-info/10 text-info border border-info/20',
  APPLIED: 'bg-accent/10 text-accent-light border border-accent/20',
  PHONE_SCREEN: 'bg-warning/10 text-warning border border-warning/20',
  INTERVIEWING: 'bg-accent/10 text-accent-light border border-accent/20',
  OFFERED: 'bg-success/10 text-success border border-success/20',
  REJECTED: 'bg-danger/10 text-danger border border-danger/20',
  WITHDRAWN: 'bg-surface-700 text-text-muted border border-surface-600',
};

export default function Pipeline() {
  const [applications, setApplications] = useState<Application[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeFilter, setActiveFilter] = useState<ApplicationStatus | 'ALL'>('ALL');

  useEffect(() => {
    loadApplications();
  }, []);

  async function loadApplications() {
    setLoading(true);
    setError(null);
    try {
      const data = await api.pipeline.list();
      setApplications(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load pipeline');
    } finally {
      setLoading(false);
    }
  }

  async function moveToStage(appId: string, newStatus: ApplicationStatus) {
    try {
      const updated = await api.pipeline.updateStatus(appId, newStatus);
      setApplications((prev) =>
        prev.map((a) => (a.id === appId ? updated : a)),
      );
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to update status');
    }
  }

  const filtered = activeFilter === 'ALL'
    ? applications
    : applications.filter((a) => a.status === activeFilter);

  return (
    <div>
      <h1 className="text-2xl font-bold text-text-primary mb-6">Application Pipeline</h1>

      {error && (
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm mb-4">
          {error}
        </div>
      )}

      <div className="flex gap-2 mb-6">
        {STATUSES.map((s) => (
          <button
            key={s}
            onClick={() => setActiveFilter(s)}
            className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
              activeFilter === s
                ? 'bg-accent text-white'
                : 'bg-surface-700 text-text-secondary hover:bg-surface-600'
            }`}
          >
            {s === 'ALL' ? 'All' : stageLabels[s]}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="text-text-muted text-center py-12">Loading...</div>
      ) : filtered.length === 0 ? (
        <div className="text-center py-12">
          <p className="text-text-muted text-lg mb-2">No applications tracked yet</p>
          <p className="text-text-muted text-sm">Browse jobs and track your applications.</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          {filtered.map((app) => (
            <ApplicationCard
              key={app.id}
              application={app}
              onMove={moveToStage}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function ApplicationCard({
  application,
  onMove,
}: {
  application: Application;
  onMove: (id: string, stage: ApplicationStatus) => void;
}) {
  const nextStageMap: Partial<Record<ApplicationStatus, ApplicationStatus>> = {
    INTERESTED: 'APPLIED',
    APPLIED: 'PHONE_SCREEN',
    PHONE_SCREEN: 'INTERVIEWING',
    INTERVIEWING: 'OFFERED',
  };
  const nextStage = nextStageMap[application.status];

  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-4 hover:border-surface-500 transition-colors">
      <div className="flex items-start justify-between gap-2 mb-2">
        <div className="min-w-0">
          <p className="text-sm font-medium text-text-primary truncate">{application.job.title}</p>
          <p className="text-xs text-text-secondary mt-0.5">{application.job.companyName}</p>
        </div>
        <span className={`shrink-0 text-xs px-2 py-0.5 rounded-md font-medium ${statusColors[application.status]}`}>
          {stageLabels[application.status]}
        </span>
      </div>

      {application.appliedDate && (
        <p className="text-xs text-text-muted mt-2 font-mono">{application.appliedDate}</p>
      )}

      {application.notes && (
        <p className="text-xs text-text-muted mt-1.5 italic truncate">{application.notes}</p>
      )}

      {nextStage && (
        <button
          onClick={() => onMove(application.id, nextStage)}
          className="mt-3 text-xs text-accent hover:text-accent-light transition-colors font-medium"
        >
          Move to {stageLabels[nextStage]} &rarr;
        </button>
      )}
    </div>
  );
}
