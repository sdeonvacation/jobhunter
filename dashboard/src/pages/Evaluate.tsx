import { useState, useEffect, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import type { EvaluationResult, EvaluationBlock } from '../types/careerOps';
import type { Job, SuggestedContact } from '../types';
import { careerOps } from '../api/careerOps';
import { api } from '../api/client';
import EvaluationBadge from '../components/EvaluationBadge';
import LivenessBadge from '../components/LivenessBadge';
import type { LivenessResult } from '../types/careerOps';

function renderValue(value: unknown): React.ReactNode {
  if (value === null || value === undefined) return <span className="text-text-muted italic">—</span>;
  if (typeof value === 'string') return <span>{value}</span>;
  if (typeof value === 'number') return <span className="font-mono">{value}</span>;
  if (typeof value === 'boolean') return <span className="font-mono">{value ? '✓' : '✗'}</span>;
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-text-muted italic">none</span>;
    return (
      <ul className="space-y-1 mt-1">
        {value.map((item, i) => (
          <li key={i} className="flex gap-2 text-sm">
            <span className="text-accent shrink-0">•</span>
            <span className="text-text-secondary">{typeof item === 'object' ? JSON.stringify(item) : String(item)}</span>
          </li>
        ))}
      </ul>
    );
  }
  if (typeof value === 'object') {
    return (
      <div className="pl-3 border-l border-surface-600 mt-1 space-y-2">
        {Object.entries(value as Record<string, unknown>).map(([k, v]) => (
          <div key={k}>
            <span className="text-text-muted text-xs uppercase tracking-wide">{formatKey(k)}</span>
            <div className="text-text-secondary text-sm">{renderValue(v)}</div>
          </div>
        ))}
      </div>
    );
  }
  return <span>{String(value)}</span>;
}

function formatKey(key: string): string {
  return key.replace(/([A-Z])/g, ' $1').replace(/^./, s => s.toUpperCase()).trim();
}

function BlockAccordion({ block, label, defaultOpen }: { block: EvaluationBlock; label: string; defaultOpen?: boolean }) {
  const [open, setOpen] = useState(defaultOpen ?? false);
  const entries = Object.entries(block).filter(([k]) => k !== 'error');
  const hasError = 'error' in block;

  return (
    <div className="bg-surface-800 rounded-xl border border-surface-700 overflow-hidden">
      <button
        onClick={() => setOpen(!open)}
        className="w-full flex items-center justify-between px-5 py-4 text-left hover:bg-surface-700/50 transition-colors"
      >
        <div className="flex items-center gap-3">
          <span className="text-text-primary font-medium">{label}</span>
          {hasError && <span className="text-xs text-danger bg-danger/10 px-2 py-0.5 rounded-full">error</span>}
          {!hasError && typeof block.overallFit === 'number' && (
            <span className="text-xs font-mono text-text-muted bg-surface-700 px-2 py-0.5 rounded-full">
              {block.overallFit as number}/5
            </span>
          )}
          {!hasError && typeof block.confidence === 'number' && (
            <span className="text-xs font-mono text-text-muted bg-surface-700 px-2 py-0.5 rounded-full">
              confidence: {block.confidence as number}/5
            </span>
          )}
          {!hasError && typeof block.tier === 'string' && (
            <span className={`text-xs px-2 py-0.5 rounded-full ${
              block.tier === 'GREEN' ? 'bg-success/10 text-success' :
              block.tier === 'AMBER' ? 'bg-warning/10 text-warning' : 'bg-danger/10 text-danger'
            }`}>
              {block.tier as string}
            </span>
          )}
        </div>
        <svg
          className={`w-4 h-4 text-text-muted transition-transform ${open ? 'rotate-180' : ''}`}
          fill="none"
          viewBox="0 0 24 24"
          stroke="currentColor"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {open && (
        <div className="px-5 pb-4 border-t border-surface-700 pt-3 space-y-3">
          {hasError && (
            <p className="text-danger text-sm">{String(block.error)}</p>
          )}
          {entries.map(([key, value]) => (
            <div key={key}>
              <h4 className="text-xs text-text-muted uppercase tracking-wide font-medium mb-1">{formatKey(key)}</h4>
              <div className="text-text-secondary text-sm">{renderValue(value)}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function Evaluate() {
  const { jobId } = useParams<{ jobId: string }>();
  const [evaluation, setEvaluation] = useState<EvaluationResult | null>(null);
  const [job, setJob] = useState<Job | null>(null);
  const [liveness, setLiveness] = useState<LivenessResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [evaluating, setEvaluating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [contacts, setContacts] = useState<SuggestedContact[]>([]);
  const [contactsLoading, setContactsLoading] = useState(false);
  const [contactsError, setContactsError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    if (!jobId) return;
    setLoading(true);
    try {
      const [jobData, evalData, livenessData] = await Promise.allSettled([
        api.jobs.getById(jobId),
        careerOps.getEvaluation(jobId),
        careerOps.getLiveness(jobId),
      ]);
      if (jobData.status === 'fulfilled') setJob(jobData.value);
      if (evalData.status === 'fulfilled') setEvaluation(evalData.value);
      if (livenessData.status === 'fulfilled') setLiveness(livenessData.value);
    } catch (err) {
      setError('Failed to load evaluation data');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const handleEvaluate = async () => {
    if (!jobId) return;
    setEvaluating(true);
    setError(null);
    try {
      const result = await careerOps.evaluateJob(jobId);
      setEvaluation(result);
    } catch (err) {
      setError('Evaluation failed. Please try again.');
      console.error(err);
    } finally {
      setEvaluating(false);
    }
  };

  const handleFindContacts = async () => {
    if (!jobId) return;
    setContactsLoading(true);
    setContactsError(null);
    try {
      const result = await api.people.suggestContacts(jobId);
      setContacts(result);
    } catch (err) {
      setContactsError('Failed to find contacts.');
      console.error(err);
    } finally {
      setContactsLoading(false);
    }
  };

  if (loading) {
    return <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>;
  }

  const blocks: { key: string; label: string; block: EvaluationBlock | undefined }[] = evaluation
    ? [
        { key: 'A', label: 'A. Role Summary', block: evaluation.roleSummary },
        { key: 'B', label: 'B. CV Match Analysis', block: evaluation.cvMatch },
        { key: 'C', label: 'C. Level Strategy', block: evaluation.levelStrategy },
        { key: 'D', label: 'D. Company Research', block: evaluation.compResearch },
        { key: 'E', label: 'E. Customization Plan', block: evaluation.customizationPlan },
        { key: 'F', label: 'F. Interview Plan', block: evaluation.interviewPlan },
        { key: 'G', label: 'G. Legitimacy Check', block: evaluation.legitimacy },
      ]
    : [];

  return (
    <div>
      {/* Header */}
      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold text-text-primary">
            {evaluation?.jobTitle ?? job?.title ?? 'Job Evaluation'}
          </h1>
          {(evaluation?.companyName || job) && (
            <p className="text-text-secondary mt-1">
              {evaluation?.companyName ?? job?.companyName}
              {job?.location && <span className="text-text-muted"> · {job.location}</span>}
            </p>
          )}
        </div>
        <div className="flex items-center gap-3">
          {liveness && <LivenessBadge status={liveness.status} checkedAt={liveness.checkedAt} />}
          {evaluation && (
            <EvaluationBadge
              score={evaluation.overallScore}
              archetype={evaluation.archetype}
              legitimacyTier={evaluation.legitimacyTier}
            />
          )}
        </div>
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 mb-6 text-danger text-sm">
          {error}
        </div>
      )}

      {/* No evaluation yet */}
      {!evaluation && !evaluating && (
        <div className="text-center py-16 animate-fade-in">
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            <svg width="40" height="40" viewBox="0 0 40 40" fill="none" className="text-accent">
              <path d="M20 8v24M8 20h24" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-secondary text-lg mb-4 font-medium">No evaluation yet</p>
          <button
            onClick={handleEvaluate}
            className="bg-accent hover:bg-accent/90 text-white px-6 py-2.5 rounded-lg font-medium transition-colors"
          >
            Evaluate Job
          </button>
        </div>
      )}

      {/* Evaluating state */}
      {evaluating && (
        <div className="text-center py-16">
          <div className="mx-auto w-16 h-16 mb-5 rounded-full bg-accent/10 flex items-center justify-center animate-pulse-soft">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none" className="text-accent animate-spin">
              <circle cx="16" cy="16" r="12" stroke="currentColor" strokeWidth="2" opacity="0.3" />
              <path d="M16 4a12 12 0 0 1 12 12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-secondary text-lg font-medium">Evaluating...</p>
          <p className="text-text-muted text-sm mt-2">This may take 10-20 seconds</p>
        </div>
      )}

      {/* Evaluation results */}
      {evaluation && (
        <div className="space-y-4">
          <div className="space-y-3">
            {blocks.map(({ key, label, block }, i) =>
              block ? (
                <div key={key} className="animate-slide-up" style={{ animationDelay: `${i * 50}ms` }}>
                  <BlockAccordion block={block} label={label} defaultOpen={i === 1} />
                </div>
              ) : null,
            )}
          </div>

          {/* Footer metadata */}
          <div className="flex items-center gap-4 pt-4 text-xs text-text-muted">
            <span>Evaluated: {new Date(evaluation.evaluatedAt).toLocaleString()}</span>
            {evaluation.archetype && <span>Archetype: {evaluation.archetype}</span>}
            {evaluation.legitimacyTier && <span>Legitimacy: {evaluation.legitimacyTier}</span>}
          </div>
        </div>
      )}

      {/* Suggested Contacts Section */}
      {evaluation && (
        <div className="mt-8 border-t border-surface-700 pt-6">
          <div className="flex items-center justify-between mb-4">
            <h2 className="text-lg font-semibold text-text-primary">Suggested Contacts</h2>
            {contacts.length === 0 && !contactsLoading && (
              <button
                onClick={handleFindContacts}
                disabled={contactsLoading}
                className="bg-surface-700 hover:bg-surface-600 text-text-primary px-4 py-2 rounded-lg text-sm font-medium transition-colors"
              >
                Find Contacts for this Role
              </button>
            )}
          </div>

          {contactsError && (
            <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 mb-4 text-danger text-sm">
              {contactsError}
            </div>
          )}

          {contactsLoading && (
            <div className="text-center py-8">
              <p className="text-text-muted animate-pulse-soft">Discovering contacts...</p>
            </div>
          )}

          {contacts.length > 0 && (
            <div className="space-y-3">
              {contacts.map((contact) => (
                <div key={contact.id} className="bg-surface-800 rounded-xl border border-surface-700 px-5 py-4 flex items-center justify-between">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-text-primary font-medium truncate">{contact.personName}</span>
                      {contact.seniority && (
                        <span className="text-xs bg-surface-700 text-text-muted px-2 py-0.5 rounded-full">
                          {contact.seniority}
                        </span>
                      )}
                      <span className="text-xs font-mono bg-accent/10 text-accent px-2 py-0.5 rounded-full">
                        {contact.contactPriorityScore}
                      </span>
                    </div>
                    {contact.title && (
                      <p className="text-sm text-text-secondary mt-0.5 truncate">{contact.title}</p>
                    )}
                    {contact.email && (
                      <div className="flex items-center gap-2 mt-1">
                        <button
                          onClick={() => navigator.clipboard.writeText(contact.email!)}
                          className="text-sm text-accent hover:text-accent/80 font-mono transition-colors"
                          title="Click to copy"
                        >
                          {contact.email}
                        </button>
                        <span className={`text-xs px-1.5 py-0.5 rounded ${
                          contact.emailConfidence === 'HIGH' ? 'bg-success/10 text-success' :
                          contact.emailConfidence === 'MEDIUM' ? 'bg-warning/10 text-warning' :
                          'bg-surface-600 text-text-muted'
                        }`}>
                          {contact.emailConfidence}
                        </span>
                      </div>
                    )}
                  </div>
                  <a
                    href={contact.linkedinUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="shrink-0 ml-4 text-text-muted hover:text-accent transition-colors"
                    title="Open LinkedIn"
                  >
                    <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
                      <path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433a2.062 2.062 0 01-2.063-2.065 2.064 2.064 0 112.063 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.222 0h.003z"/>
                    </svg>
                  </a>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
