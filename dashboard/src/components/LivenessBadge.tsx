import type { LivenessStatus } from '../types/careerOps';

interface LivenessBadgeProps {
  status: LivenessStatus;
  checkedAt?: string;
}

export default function LivenessBadge({ status, checkedAt }: LivenessBadgeProps) {
  const config: Record<LivenessStatus, { dot: string; text: string; bg: string }> = {
    ACTIVE: { dot: 'bg-success', text: 'text-success', bg: 'bg-success/10' },
    EXPIRED: { dot: 'bg-danger', text: 'text-danger', bg: 'bg-danger/10' },
    UNCERTAIN: { dot: 'bg-warning', text: 'text-warning', bg: 'bg-warning/10' },
  };

  if (!status || !config[status]) return null;

  const { dot, text, bg } = config[status];

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium ${text} ${bg}`}
      title={checkedAt ? `Checked: ${new Date(checkedAt).toLocaleDateString()}` : undefined}
    >
      <span className={`w-1.5 h-1.5 rounded-full ${dot}`} />
      {status}
    </span>
  );
}
