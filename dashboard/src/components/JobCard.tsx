import type { Job } from '../types';
import ScoreBadge from './ScoreBadge';

interface JobCardProps {
  job: Job;
  index?: number;
  onMarkApplied?: (id: string) => void;
  onUndoApplied?: (id: string) => void;
}

export default function JobCard({ job, index = 0, onMarkApplied, onUndoApplied }: JobCardProps) {
  const salary = formatSalary(job);
  const recommendation = job.recommendation;
  const delay = Math.min(index * 50, 300);

  const handleApplied = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (job.applied && onUndoApplied) {
      onUndoApplied(job.id);
    } else if (!job.applied && onMarkApplied) {
      onMarkApplied(job.id);
    }
  };

  return (
    <a
      href={job.applyUrl || '#'}
      target="_blank"
      rel="noopener noreferrer"
      className="block bg-surface-800 border border-surface-600 rounded-lg p-5 transition-all duration-200 ease-out hover:border-accent/30 hover:-translate-y-px hover:shadow-glow opacity-0 animate-slide-up"
      style={{ animationDelay: `${delay}ms` }}
    >
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <h3 className="text-base font-semibold text-text-primary truncate hover:text-accent transition-colors duration-150">
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
            {job.postedDate && (
              <span className="text-xs text-text-muted">{job.postedDate}</span>
            )}
          </div>
          {job.topSkills && job.topSkills.length > 0 && (
            <div className="flex items-center gap-1.5 mt-2 flex-wrap">
              {job.topSkills.slice(0, 5).map((skill) => (
                <span
                  key={skill}
                  className="text-[10px] px-1.5 py-0.5 rounded bg-accent/10 text-accent-light ring-1 ring-accent/20 font-medium"
                >
                  {skill}
                </span>
              ))}
            </div>
          )}
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
          {(onMarkApplied || onUndoApplied) && (
            <button
              onClick={handleApplied}
              title={job.applied ? 'Undo applied' : 'Mark as applied'}
              className={`w-8 h-8 flex items-center justify-center rounded-md border transition-all duration-150 ${
                job.applied
                  ? 'bg-success/20 border-success/40 text-success'
                  : 'border-surface-600 text-text-muted hover:border-success/40 hover:text-success hover:bg-success/10'
              }`}
            >
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="3.5 8 6.5 11 12.5 5" />
              </svg>
            </button>
          )}
        </div>
      </div>
    </a>
  );
}

function RecommendationBadge({ recommendation }: { recommendation: string }) {
  const styles: Record<string, string> = {
    APPLY: 'bg-success/10 text-success ring-1 ring-success/30',
    MAYBE: 'bg-warning/10 text-warning ring-1 ring-warning/30',
    SKIP: 'bg-surface-700 text-text-muted ring-1 ring-surface-600',
  };
  const dots: Record<string, string> = {
    APPLY: 'bg-success',
    MAYBE: 'bg-warning',
  };
  return (
    <span
      className={`inline-flex items-center gap-1.5 text-xs px-2 py-0.5 rounded-full font-medium ${styles[recommendation] || 'bg-surface-700 text-text-muted'}`}
    >
      {dots[recommendation] && (
        <span className={`w-1.5 h-1.5 rounded-full ${dots[recommendation]}`} />
      )}
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
