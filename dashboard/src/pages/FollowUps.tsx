import { useState, useEffect, useCallback } from 'react';
import type { FollowUpItem, FollowUpSchedule, FollowUpStatus } from '../types/careerOps';
import { careerOps } from '../api/careerOps';

const STATUS_TABS: { label: string; value: FollowUpStatus | '' }[] = [
  { label: 'All', value: '' },
  { label: 'Overdue', value: 'OVERDUE' },
  { label: 'Pending', value: 'PENDING' },
  { label: 'Sent', value: 'SENT' },
];

function statusBadge(status: FollowUpStatus) {
  const config: Record<FollowUpStatus, { text: string; bg: string }> = {
    OVERDUE: { text: 'text-danger', bg: 'bg-danger/10' },
    PENDING: { text: 'text-warning', bg: 'bg-warning/10' },
    SENT: { text: 'text-success', bg: 'bg-success/10' },
    SKIPPED: { text: 'text-text-muted', bg: 'bg-surface-700' },
  };
  const { text, bg } = config[status];
  return `inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium ${text} ${bg}`;
}

function daysSince(dateStr: string): number {
  const date = new Date(dateStr);
  const now = new Date();
  return Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
}

export default function FollowUps() {
  const [schedule, setSchedule] = useState<FollowUpSchedule | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<FollowUpStatus | ''>('');

  const fetchFollowUps = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await careerOps.getFollowUps(activeTab || undefined);
      setSchedule(result);
    } catch (err) {
      setError('Failed to load follow-ups');
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [activeTab]);

  useEffect(() => {
    fetchFollowUps();
  }, [fetchFollowUps]);

  const handleMarkSent = async (item: FollowUpItem) => {
    try {
      await careerOps.markFollowUpSent(item.id);
      setSchedule((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          followUps: prev.followUps.map((f) =>
            f.id === item.id ? { ...f, status: 'SENT' as FollowUpStatus } : f,
          ),
          overdueCount: item.status === 'OVERDUE' ? prev.overdueCount - 1 : prev.overdueCount,
        };
      });
    } catch (err) {
      console.error('Failed to mark follow-up as sent', err);
    }
  };

  return (
    <div>
      {/* Header */}
      <div className="flex items-baseline gap-3 mb-6">
        <h1 className="text-2xl font-bold text-text-primary">Follow-Ups</h1>
        {schedule && schedule.overdueCount > 0 && (
          <span className="text-sm font-mono text-danger bg-danger/10 px-2 py-0.5 rounded-full">
            {schedule.overdueCount} overdue
          </span>
        )}
      </div>

      {/* Status tabs */}
      <div className="flex gap-1 mb-6 bg-surface-800 rounded-lg p-1 w-fit">
        {STATUS_TABS.map((tab) => (
          <button
            key={tab.value}
            onClick={() => setActiveTab(tab.value)}
            className={`px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
              activeTab === tab.value
                ? 'bg-surface-700 text-text-primary'
                : 'text-text-muted hover:text-text-secondary'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {error && (
        <div className="bg-danger/10 border border-danger/30 rounded-lg px-4 py-3 mb-6 text-danger text-sm">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-text-muted text-center py-12 animate-pulse-soft">Loading...</div>
      ) : !schedule || schedule.followUps.length === 0 ? (
        <div className="text-center py-16 animate-fade-in">
          <div className="mx-auto w-20 h-20 mb-5 rounded-full bg-surface-700/50 flex items-center justify-center">
            <svg width="40" height="40" viewBox="0 0 40 40" fill="none" className="text-accent">
              <circle cx="20" cy="20" r="16" stroke="currentColor" strokeWidth="1.5" opacity="0.3" />
              <path d="M20 12v8l5 3" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
            </svg>
          </div>
          <p className="text-text-secondary text-lg mb-2 font-medium">No follow-ups</p>
          <p className="text-text-muted text-sm max-w-xs mx-auto">
            Follow-ups will appear here once you have pending applications.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {schedule.followUps.map((item, i) => (
            <div
              key={item.id}
              className="bg-surface-800 rounded-xl border border-surface-700 p-4 flex items-center justify-between animate-slide-up"
              style={{ animationDelay: `${i * 50}ms` }}
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="text-text-primary font-medium text-sm truncate">{item.jobTitle}</span>
                  <span className={statusBadge(item.status)}>{item.status}</span>
                </div>
                <div className="flex items-center gap-3 text-xs text-text-muted">
                  <span>{item.companyName}</span>
                  <span>·</span>
                  <span>{daysSince(item.scheduledDate)}d since scheduled</span>
                  <span>·</span>
                  <span>Follow-up #{item.count}</span>
                  <span>·</span>
                  <span>{new Date(item.scheduledDate).toLocaleDateString()}</span>
                </div>
              </div>
              {item.status !== 'SENT' && (
                <button
                  onClick={() => handleMarkSent(item)}
                  className="ml-4 shrink-0 bg-surface-700 hover:bg-surface-600 text-text-secondary text-xs px-3 py-1.5 rounded-md transition-colors"
                >
                  Mark Sent
                </button>
              )}
            </div>
          ))}
        </div>
      )}

      {/* Total count */}
      {schedule && schedule.total > 0 && (
        <div className="mt-4 text-center text-xs text-text-muted">
          Showing {schedule.followUps.length} of {schedule.total} follow-ups
        </div>
      )}
    </div>
  );
}
