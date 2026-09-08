import { useState } from 'react';
import type { RecruiterPostCheckResult } from '../types';
import { api } from '../api/client';

interface Props {
  result: RecruiterPostCheckResult;
}

const CONNECT_NOTE = 'Hi! I saw your post about this opening and would love to connect.';

const CONNECT_FEEDBACK: Record<string, string> = {
  SENT: 'Request sent',
  ALREADY_CONNECTED: 'Already connected',
  DAILY_LIMIT_REACHED: 'Daily limit reached',
  FAILED: 'Failed',
};

export default function DigestRecruiterBlock({ result }: Props) {
  const [connectState, setConnectState] = useState<string | null>(null);
  const best = result.matchedPosts?.[0];
  if (!best) return null;

  const handleConnect = async (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    if (!result.contactId) return;
    try {
      const res = await api.linkedin.connectContact(result.contactId, CONNECT_NOTE);
      setConnectState(res.status);
    } catch {
      setConnectState('FAILED');
    }
  };

  const isHigh = result.verdict === 'HIGH';
  const snippet = best.snippet && best.snippet.length > 160 ? `${best.snippet.slice(0, 160)}…` : best.snippet;

  return (
    <div className="mt-3 rounded-md border border-surface-600 bg-surface-700/50 p-3">
      <div className="flex items-center gap-2">
        <span className="text-xs font-medium text-text-secondary">Recruiter posted</span>
        <span
          className={`text-[10px] px-1.5 py-0.5 rounded-full ring-1 font-medium ${
            isHigh ? 'bg-success/10 text-success ring-success/30' : 'bg-warning/10 text-warning ring-warning/30'
          }`}
        >
          {result.verdict}
        </span>
      </div>
      <p className="text-sm font-medium text-text-primary mt-1.5">{best.authorName}</p>
      {best.authorTitle && <p className="text-xs text-text-muted">{best.authorTitle}</p>}
      {snippet && <p className="text-xs text-text-secondary mt-1">{snippet}</p>}
      {best.postedAt && <p className="text-xs text-text-muted mt-1">posted {best.postedAt}</p>}
      <div className="flex items-center gap-3 mt-2">
        <a
          href={best.postUrl}
          target="_blank"
          rel="noopener noreferrer"
          onClick={(e) => {
            e.preventDefault();
            e.stopPropagation();
          }}
          className="text-xs text-accent hover:underline"
        >
          View post
        </a>
        {result.contactId && (
          <button
            type="button"
            onClick={handleConnect}
            disabled={connectState !== null}
            className="text-xs px-2 py-1 rounded bg-accent/10 text-accent ring-1 ring-accent/20 hover:bg-accent/20 transition-colors disabled:opacity-60"
          >
            {connectState ? (CONNECT_FEEDBACK[connectState] ?? connectState) : 'Connect'}
          </button>
        )}
      </div>
    </div>
  );
}