import { useState, useEffect } from 'react';
import type { Application, ApplicationStatus } from '../types';
import { api } from '../api/client';

const PIPELINE_STAGES: ApplicationStatus[] = [
  'INTERESTED',
  'APPLIED',
  'PHONE_SCREEN',
  'INTERVIEWING',
  'OFFERED',
];

const stageLabels: Record<ApplicationStatus, string> = {
  INTERESTED: 'Interested',
  APPLIED: 'Applied',
  PHONE_SCREEN: 'Phone Screen',
  INTERVIEWING: 'Interviewing',
  OFFERED: 'Offered',
  REJECTED: 'Rejected',
  WITHDRAWN: 'Withdrawn',
};

export default function Pipeline() {
  const [applications, setApplications] = useState<Application[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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

  const grouped = PIPELINE_STAGES.reduce(
    (acc, stage) => {
      acc[stage] = applications.filter((a) => a.status === stage);
      return acc;
    },
    {} as Record<ApplicationStatus, Application[]>,
  );

  if (loading) return <div className="text-center py-8 text-gray-500">Loading...</div>;

  return (
    <div>
      <h1 className="text-2xl font-bold mb-4">Application Pipeline</h1>

      {error && (
        <div className="bg-red-50 border border-red-200 text-red-700 rounded p-3 mb-4 text-sm">
          {error}
        </div>
      )}

      <div className="flex gap-4 overflow-x-auto pb-4">
        {PIPELINE_STAGES.map((stage) => (
          <div
            key={stage}
            className="flex-shrink-0 w-64 bg-gray-100 rounded-lg p-3"
          >
            <h3 className="text-sm font-semibold text-gray-700 mb-3 flex items-center justify-between">
              {stageLabels[stage]}
              <span className="bg-gray-300 text-gray-700 text-xs px-2 py-0.5 rounded-full">
                {grouped[stage]?.length || 0}
              </span>
            </h3>

            <div className="space-y-2">
              {(grouped[stage] || []).map((app) => (
                <PipelineCard
                  key={app.id}
                  application={app}
                  currentStage={stage}
                  onMove={moveToStage}
                />
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function PipelineCard({
  application,
  currentStage,
  onMove,
}: {
  application: Application;
  currentStage: ApplicationStatus;
  onMove: (id: string, stage: ApplicationStatus) => void;
}) {
  const stageIdx = PIPELINE_STAGES.indexOf(currentStage);
  const nextStage = stageIdx < PIPELINE_STAGES.length - 1 ? PIPELINE_STAGES[stageIdx + 1] : null;

  return (
    <div className="bg-white rounded border border-gray-200 p-3 shadow-sm">
      <p className="text-sm font-medium text-gray-900 truncate">{application.job.title}</p>
      <p className="text-xs text-gray-500">{application.job.company.name}</p>
      {application.appliedDate && (
        <p className="text-xs text-gray-400 mt-1">{application.appliedDate}</p>
      )}
      {application.notes && (
        <p className="text-xs text-gray-500 mt-1 truncate italic">{application.notes}</p>
      )}
      {nextStage && (
        <button
          onClick={() => onMove(application.id, nextStage)}
          className="mt-2 text-xs text-blue-600 hover:text-blue-800"
        >
          Move to {stageLabels[nextStage]} →
        </button>
      )}
    </div>
  );
}
