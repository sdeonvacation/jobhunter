import type { Job } from '../types';
import ScoreBadge from './ScoreBadge';

interface JobCardProps {
  job: Job;
  expanded?: boolean;
  onToggle: () => void;
  onApply?: (jobId: string) => void;
}

export default function JobCard({ job, expanded, onToggle, onApply }: JobCardProps) {
  const salary = formatSalary(job);
  const recommendation = job.matchScore?.recommendation;

  return (
    <div
      className="bg-white border border-gray-200 rounded-lg p-4 shadow-sm hover:shadow-md transition-shadow cursor-pointer"
      onClick={onToggle}
    >
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <h3 className="text-base font-semibold text-gray-900 truncate">{job.title}</h3>
          <p className="text-sm text-gray-600">{job.company.name}</p>
          <div className="flex items-center gap-2 mt-1 text-xs text-gray-500">
            {job.location && <span>{job.location}</span>}
            {job.isRemote && <span className="bg-blue-50 text-blue-700 px-1.5 py-0.5 rounded">{job.isRemote}</span>}
            {salary && <span>{salary}</span>}
          </div>
        </div>

        <div className="flex items-center gap-2 ml-3 shrink-0">
          {job.opportunityScore && (
            <ScoreBadge score={job.opportunityScore.score} label="Opp" />
          )}
          {job.matchScore && (
            <ScoreBadge score={job.matchScore.overallScore} label="Match" size="sm" />
          )}
          {recommendation && (
            <RecommendationBadge recommendation={recommendation} />
          )}
        </div>
      </div>

      {expanded && (
        <div className="mt-4 pt-4 border-t border-gray-100" onClick={(e) => e.stopPropagation()}>
          {job.description && (
            <div
              className="text-sm text-gray-700 mb-4 max-h-64 overflow-y-auto prose prose-sm"
              dangerouslySetInnerHTML={{ __html: job.description }}
            />
          )}
          <div className="flex items-center gap-3">
            {job.applyUrl && (
              <a
                href={job.applyUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="text-sm bg-blue-600 text-white px-3 py-1.5 rounded hover:bg-blue-700"
              >
                Apply Externally
              </a>
            )}
            {onApply && (
              <button
                onClick={() => onApply(job.id)}
                className="text-sm bg-green-600 text-white px-3 py-1.5 rounded hover:bg-green-700"
              >
                Track Application
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function RecommendationBadge({ recommendation }: { recommendation: string }) {
  const styles: Record<string, string> = {
    APPLY: 'bg-green-600 text-white',
    MAYBE: 'bg-yellow-500 text-white',
    SKIP: 'bg-gray-400 text-white',
  };
  return (
    <span className={`text-xs px-2 py-0.5 rounded font-medium ${styles[recommendation] || ''}`}>
      {recommendation}
    </span>
  );
}

function formatSalary(job: Job): string | null {
  if (!job.salaryMin && !job.salaryMax) return null;
  const currency = job.salaryCurrency || 'EUR';
  const fmt = (n: number) => n >= 1000 ? `${Math.round(n / 1000)}k` : String(n);
  if (job.salaryMin && job.salaryMax) {
    return `${currency} ${fmt(job.salaryMin)}-${fmt(job.salaryMax)}`;
  }
  return `${currency} ${fmt(job.salaryMin || job.salaryMax!)}+`;
}
