import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import type { ContactDetail as ContactDetailType, EventType, Seniority, ConnectionStatus } from '../types';
import { api } from '../api/client';

const EVENT_TYPE_OPTIONS: EventType[] = [
  'MESSAGE_SENT', 'REPLIED', 'CALL_BOOKED', 'REFERRAL_REQUESTED',
  'REFERRAL_GIVEN', 'INTERVIEW_OBTAINED', 'GHOSTED_AUTO', 'STATUS_OVERRIDE',
];

const SENIORITY_COLORS: Record<Seniority, string> = {
  RECRUITER: 'bg-info/10 text-info border border-info/20',
  MANAGER: 'bg-accent/10 text-accent border border-accent/20',
  DIRECTOR: 'bg-warning/10 text-warning border border-warning/20',
  STAFF: 'bg-success/10 text-success border border-success/20',
  SENIOR: 'bg-purple-500/10 text-purple-400 border border-purple-500/20',
  IC: 'bg-surface-700 text-text-muted border border-surface-600',
};

const CONNECTION_COLORS: Record<ConnectionStatus, string> = {
  NONE: 'bg-surface-700 text-text-muted',
  PENDING: 'bg-warning/10 text-warning',
  CONNECTED: 'bg-success/10 text-success',
  DECLINED: 'bg-danger/10 text-danger',
};

const EVENT_LABELS: Record<string, string> = {
  CONTACT_DISCOVERED: 'Discovered',
  MESSAGE_SENT: 'Message Sent',
  REPLIED: 'Replied',
  CALL_BOOKED: 'Call Booked',
  REFERRAL_REQUESTED: 'Referral Requested',
  REFERRAL_GIVEN: 'Referral Given',
  INTERVIEW_OBTAINED: 'Interview Obtained',
  GHOSTED_AUTO: 'Ghosted',
  STATUS_OVERRIDE: 'Status Changed',
};

function ScoreGauge({ label, value, max = 100 }: { label: string; value: number; max?: number }) {
  const pct = Math.min(100, (value / max) * 100);
  return (
    <div className="flex-1 bg-surface-800 border border-surface-700 rounded-lg p-4">
      <p className="text-xs text-text-muted uppercase tracking-wider mb-2">{label}</p>
      <p className="text-2xl font-bold text-text-primary mb-2">{value.toFixed(1)}</p>
      <div className="w-full bg-surface-700 rounded-full h-1.5">
        <div
          className="bg-accent rounded-full h-1.5 transition-all"
          style={{ width: `${pct}%` }}
        />
      </div>
    </div>
  );
}

function formatDate(dateStr: string): string {
  const d = new Date(dateStr);
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr);
  return d.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });
}

export default function ContactDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [contact, setContact] = useState<ContactDetailType | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [eventDropdownOpen, setEventDropdownOpen] = useState(false);
  const [recordingEvent, setRecordingEvent] = useState(false);

  const loadContact = useCallback(async () => {
    if (!id) return;
    setLoading(true);
    setError(null);
    try {
      const data = await api.people.getById(id);
      setContact(data);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load contact');
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadContact();
  }, [loadContact]);

  const handleRecordEvent = useCallback(async (eventType: EventType) => {
    if (!id) return;
    setRecordingEvent(true);
    setEventDropdownOpen(false);
    try {
      await api.people.recordEvent(id, { eventType });
      await loadContact();
    } catch {
      // silent fail for now
    } finally {
      setRecordingEvent(false);
    }
  }, [id, loadContact]);

  if (loading) {
    return <div className="text-text-muted text-center py-12">Loading...</div>;
  }

  if (error || !contact) {
    return (
      <div>
        <button
          onClick={() => navigate('/people')}
          className="text-sm text-text-secondary hover:text-text-primary mb-4 inline-flex items-center gap-1"
        >
          <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
            <path d="M10 12L6 8l4-4" />
          </svg>
          Back to People
        </button>
        <div className="bg-danger/10 border border-danger/20 text-danger rounded-lg p-4 text-sm">
          {error || 'Contact not found'}
        </div>
      </div>
    );
  }

  return (
    <div>
      {/* Back navigation */}
      <button
        onClick={() => navigate('/people')}
        className="text-sm text-text-secondary hover:text-text-primary mb-6 inline-flex items-center gap-1 transition-colors"
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <path d="M10 12L6 8l4-4" />
        </svg>
        Back to People
      </button>

      {/* Header */}
      <div className="bg-surface-800 border border-surface-700 rounded-lg p-6 mb-6">
        <div className="flex items-start justify-between">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">{contact.personName}</h1>
            {contact.title && (
              <p className="text-sm text-text-secondary mt-1">{contact.title}</p>
            )}
            <div className="flex items-center gap-3 mt-3">
              <span className="text-sm text-text-muted">{contact.companyName}</span>
              {contact.seniority && (
                <span className={`text-xs px-2 py-0.5 rounded font-medium ${SENIORITY_COLORS[contact.seniority]}`}>
                  {contact.seniority}
                </span>
              )}
              <span className={`text-xs px-2 py-0.5 rounded font-medium ${CONNECTION_COLORS[contact.connectionStatus]}`}>
                {contact.connectionStatus}
              </span>
            </div>
            {contact.location && (
              <p className="text-xs text-text-muted mt-2">{contact.location}</p>
            )}
          </div>
          <div className="flex items-center gap-2">
            <a
              href={contact.linkedinUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="rounded-md px-3 py-1.5 text-xs font-medium bg-surface-700 text-text-secondary hover:bg-surface-600 transition-colors"
            >
              LinkedIn
            </a>
            {/* Record Event dropdown */}
            <div className="relative">
              <button
                onClick={() => setEventDropdownOpen(!eventDropdownOpen)}
                disabled={recordingEvent}
                className="rounded-md px-3 py-1.5 text-xs font-medium bg-accent text-white hover:bg-accent/80 transition-colors disabled:opacity-50"
              >
                {recordingEvent ? 'Recording...' : 'Record Event'}
              </button>
              {eventDropdownOpen && (
                <div className="absolute right-0 mt-1 bg-surface-800 border border-surface-600 rounded-md shadow-lg z-10 py-1 min-w-[180px]">
                  {EVENT_TYPE_OPTIONS.map((et) => (
                    <button
                      key={et}
                      onClick={() => handleRecordEvent(et)}
                      className="w-full text-left px-3 py-1.5 text-xs text-text-secondary hover:bg-surface-700 hover:text-text-primary transition-colors"
                    >
                      {EVENT_LABELS[et] || et}
                    </button>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Scores */}
      <div className="flex gap-3 mb-6">
        <ScoreGauge label="Interview Weight" value={contact.interviewGenerationWeight} />
        <ScoreGauge label="Warmth" value={contact.warmthScore} />
        <ScoreGauge label="Priority" value={contact.contactPriorityScore} />
      </div>

      {/* Tech Stack */}
      {contact.techStack && contact.techStack.length > 0 && (
        <div className="bg-surface-800 border border-surface-700 rounded-lg p-4 mb-6">
          <h2 className="text-xs text-text-muted uppercase tracking-wider font-medium mb-3">Tech Stack</h2>
          <div className="flex flex-wrap gap-1.5">
            {contact.techStack.map((tech) => (
              <span key={tech} className="text-xs px-2 py-0.5 rounded bg-surface-700 text-text-secondary">
                {tech}
              </span>
            ))}
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Timeline */}
        <div className="bg-surface-800 border border-surface-700 rounded-lg p-4">
          <h2 className="text-xs text-text-muted uppercase tracking-wider font-medium mb-4">Timeline</h2>
          {contact.events.length === 0 ? (
            <p className="text-sm text-text-muted">No events recorded</p>
          ) : (
            <div className="space-y-3">
              {contact.events.map((event) => (
                <div key={event.id} className="flex items-start gap-3">
                  <div className="w-2 h-2 rounded-full bg-accent mt-1.5 shrink-0" />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm text-text-primary font-medium">
                      {EVENT_LABELS[event.eventType] || event.eventType}
                    </p>
                    <p className="text-xs text-text-muted">
                      {formatDate(event.occurredAt)} at {formatTime(event.occurredAt)}
                    </p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Messages */}
        <div className="bg-surface-800 border border-surface-700 rounded-lg p-4">
          <h2 className="text-xs text-text-muted uppercase tracking-wider font-medium mb-4">Messages</h2>
          {contact.messages.length === 0 ? (
            <p className="text-sm text-text-muted">No messages yet</p>
          ) : (
            <div className="space-y-3">
              {contact.messages.map((msg) => (
                <div key={msg.id} className="border-l-2 border-surface-600 pl-3">
                  <div className="flex items-center gap-2 mb-1">
                    <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${
                      msg.direction === 'OUT'
                        ? 'bg-accent/10 text-accent'
                        : 'bg-success/10 text-success'
                    }`}>
                      {msg.direction}
                    </span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-surface-700 text-text-muted">
                      {msg.channel}
                    </span>
                    <span className="text-xs text-text-muted ml-auto">
                      {formatDate(msg.sentAt)}
                    </span>
                  </div>
                  {msg.content && (
                    <p className="text-xs text-text-secondary line-clamp-2">{msg.content}</p>
                  )}
                  {msg.replied && msg.repliedAt && (
                    <p className="text-[10px] text-success mt-1">
                      Replied {formatDate(msg.repliedAt)}
                    </p>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Linked Jobs */}
      {contact.linkedJobs.length > 0 && (
        <div className="bg-surface-800 border border-surface-700 rounded-lg p-4 mt-6">
          <h2 className="text-xs text-text-muted uppercase tracking-wider font-medium mb-3">Linked Jobs</h2>
          <div className="space-y-2">
            {contact.linkedJobs.map((job) => (
              <div key={job.id} className="flex items-center justify-between py-2 border-b border-surface-700 last:border-0">
                <div>
                  <p className="text-sm text-text-primary">{job.title}</p>
                  <p className="text-xs text-text-muted">
                    {job.companyName}{job.location ? ` - ${job.location}` : ''}
                  </p>
                </div>
                {job.postedDate && (
                  <span className="text-xs text-text-muted">{formatDate(job.postedDate)}</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
