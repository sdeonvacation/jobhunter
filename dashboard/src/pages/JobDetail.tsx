import { useState, useEffect, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { Job } from '../types';
import { api } from '../api/client';
import ScoreBadge from '../components/ScoreBadge';

function formatSalary(job: Job): string | null {
  if (!job.salaryMin && !job.salaryMax) return null;
  const currency = job.salaryCurrency || 'EUR';
  const fmt = (n: number) => (n >= 1000 ? `${Math.round(n / 1000)}k` : String(n));
  if (job.salaryMin && job.salaryMax) {
    return `${currency} ${fmt(job.salaryMin)}-${fmt(job.salaryMax)}`;
  }
  return `${currency} ${fmt(job.salaryMin || job.salaryMax!)}`;
}

export default function JobDetail() {
  const { jobId } = useParams<{ jobId: string }>();
  const [job, setJob] = useState<Job | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const fetchData = useCallback(async () => {
    if (!jobId) return;
    setLoading(true);
    setError(null);
    try {
      const jobData = await api.jobs.getById(jobId);
      setJob(jobData);
    } catch (err) {
      setError('Failed to load job details');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleMarkApplied = async (e: React.MouseEvent) => {
    e.preventDefault();
    if (!job || saving) return;
    setSaving(true);
    try {
      await api.jobs.markApplied(job.id, !job.applied);
      setJob({ ...job, applied: !job.applied });
    } catch (err) {
      console.error(err);
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="max-w-4xl mx-auto">
        <div className="skeleton h-8 w-48 mb-4" />
        <div className="skeleton h-64 w-full" />
      </div>
    );
  }

  if (error || !job) {
    return (
      <div className="max-w-4xl mx-auto">
        <Link to="/jobs" className="text-sm text-accent hover:text-accent-light transition-colors">
          &larr; Back to jobs
        </Link>
        <p className="text-danger mt-4">{error || 'Job not found'}</p>
      </div>
    );
  }

  const salary = formatSalary(job);

  return (
    <div className="max-w-4xl mx-auto">
      <Link
        to="/jobs"
        className="inline-flex items-center text-sm text-text-secondary hover:text-accent transition-colors mb-6"
      >
        &larr; Back to jobs
      </Link>

      <div className="card p-6 mb-6">
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="min-w-0">
            <h1 className="text-2xl font-semibold text-text-primary">{job.title}</h1>
            <p className="text-text-secondary mt-1">{job.companyName}</p>
            <div className="flex items-center gap-3 mt-2 flex-wrap text-sm text-text-muted">
              {job.location && <span>{job.location}</span>}
              {job.remoteType && (
                <span className="bg-info/10 text-info text-xs px-2 py-0.5 rounded-full ring-1 ring-info/20">
                  {job.remoteType}
                </span>
              )}
              {salary && <span className="text-text-secondary font-mono">{salary}</span>}
              {job.postedDate && <span>{job.postedDate}</span>}
            </div>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            {job.opportunityScore > 0 && (
              <ScoreBadge score={job.opportunityScore} label="Opp" />
            )}
            {job.matchScore > 0 && (
              <ScoreBadge score={job.matchScore} label="Match" size="sm" />
            )}
            {job.recommendation && (
              <span
                className={`inline-flex items-center gap-1.5 text-xs px-2 py-0.5 rounded-full font-medium ${
                  job.recommendation === 'APPLY'
                    ? 'bg-success/10 text-success ring-1 ring-success/30'
                    : job.recommendation === 'MAYBE'
                      ? 'bg-warning/10 text-warning ring-1 ring-warning/30'
                      : 'bg-surface-700 text-text-muted ring-1 ring-surface-600'
                }`}
              >
                {job.recommendation}
              </span>
            )}
          </div>
        </div>

        <div className="flex items-center gap-3 mt-6 flex-wrap">
          {job.applyUrl && (
            <a
              href={job.applyUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="btn-primary"
            >
              Apply
            </a>
          )}
          <Link to={`/cover-letter/${job.id}`} className="btn-secondary">
            Cover Letter
          </Link>
          <Link to={`/evaluate/${job.id}`} className="btn-secondary">
            Evaluate
          </Link>
          <Link
            to={`/people?company=${encodeURIComponent(job.companyName || '')}`}
            className="btn-secondary"
          >
            Find People
          </Link>
          <button
            onClick={handleMarkApplied}
            disabled={saving}
            className={`btn-secondary disabled:opacity-50 ${
              job.applied ? 'text-success' : ''
            }`}
          >
            {job.applied ? 'Undo Applied' : 'Mark Applied'}
          </button>
        </div>
      </div>

      <div className="card p-6">
        <h2 className="text-lg font-semibold text-text-primary mb-4">Job Description</h2>
        {job.description ? (
          <div
            className="job-description"
            dangerouslySetInnerHTML={{ __html: job.description }}
          />
        ) : (
          <p className="text-text-muted italic">No description available.</p>
        )}
      </div>
    </div>
  );
}
