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
  const recommendation = job.recommendation;

  return (
    <div
      className="bg-surface-800 border border-surface-600 rounded-lg p-5 hover:border-accent/30 transition-all cursor-pointer"
      onClick={onToggle}
    >
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <h3 className="text-base font-semibold text-text-primary truncate">
            {job.title}
          </h3>
          <p className="text-sm text-text-secondary mt-0.5">{job.companyName}</p>
          <div className="flex items-center gap-2 mt-2 flex-wrap">
            {job.location && (
              <span className="text-xs text-text-muted">{job.location}</span>
            )}
            {job.remoteType && (
              <span className="bg-info/10 text-info text-xs px-2 py-0.5 rounded-full ring-1 ring-info/20">
                {job.remoteType}
              </span>
            )}
            {salary && (
              <span className="text-xs text-text-secondary font-mono">
                {salary}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-2 ml-3 shrink-0">
          {job.opportunityScore > 0 && (
            <ScoreBadge score={job.opportunityScore} label="Opp" />
          )}
          {job.matchScore > 0 && (
            <ScoreBadge score={job.matchScore} label="Match" size="sm" />
          )}
          {recommendation && (
            <RecommendationBadge recommendation={recommendation} />
          )}
        </div>
      </div>

      {expanded && (
        <div
          className="mt-4 pt-4 border-t border-surface-600"
          onClick={(e) => e.stopPropagation()}
        >
          {job.description && (
            <div
              className="text-sm text-text-secondary mb-4 max-h-64 overflow-y-auto leading-relaxed"
              dangerouslySetInnerHTML={{ __html: job.description }}
            />
          )}
          <div className="flex items-center gap-3">
            {job.applyUrl && (
              <a
                href={job.applyUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="btn-primary inline-flex items-center gap-1.5"
              >
                Apply Externally
              </a>
            )}
            {onApply && (
              <button
                onClick={() => onApply(job.id)}
                className="bg-success/10 text-success hover:bg-success/20 ring-1 ring-success/30 rounded-md px-4 py-2 text-sm font-medium transition-colors"
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
    APPLY: 'bg-success/10 text-success ring-1 ring-success/30',
    MAYBE: 'bg-warning/10 text-warning ring-1 ring-warning/30',
    SKIP: 'bg-surface-700 text-text-muted ring-1 ring-surface-600',
  };
  return (
    <span
      className={`text-xs px-2 py-0.5 rounded-full font-medium ${styles[recommendation] || 'bg-surface-700 text-text-muted'}`}
    >
      {recommendation}
    </span>
  );
}

function formatSalary(job: Job): string | null {
  if (!job.salaryMin && !job.salaryMax) return null;
  const currency = job.salaryCurrency || 'EUR';
  const fmt = (n: number) => (n >= 1000 ? `${Math.round(n / 1000)}k` : String(n));
  if (job.salaryMin && job.salaryMax) {
    return `${currency} ${fmt(job.salaryMin)}-${fmt(job.salaryMax)}`;
  }
  return `${currency} ${fmt(job.salaryMin || job.salaryMax!)}+`;
}
