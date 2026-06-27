import type { LegitimacyTier } from '../types/careerOps';

interface EvaluationBadgeProps {
  score: number;
  archetype?: string;
  legitimacyTier?: LegitimacyTier;
}

export default function EvaluationBadge({ score, archetype, legitimacyTier }: EvaluationBadgeProps) {
  const scoreColor =
    score >= 5
      ? 'text-success bg-success/10 ring-success/30'
      : score >= 4
        ? 'text-blue-400 bg-blue-400/10 ring-blue-400/30'
        : score >= 3
          ? 'text-warning bg-warning/10 ring-warning/30'
          : score >= 2
            ? 'text-orange-400 bg-orange-400/10 ring-orange-400/30'
            : 'text-danger bg-danger/10 ring-danger/30';

  const legitimacyDot =
    legitimacyTier === 'GREEN'
      ? 'bg-success'
      : legitimacyTier === 'AMBER'
        ? 'bg-warning'
        : 'bg-danger';

  return (
    <div className="inline-flex flex-col items-center gap-1">
      <span
        className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full ring-1 font-mono font-bold text-lg ${scoreColor}`}
      >
        {score}
        {legitimacyTier && (
          <span className={`w-2 h-2 rounded-full ${legitimacyDot}`} title={`Legitimacy: ${legitimacyTier}`} />
        )}
      </span>
      {archetype && (
        <span className="text-[10px] uppercase tracking-wide text-text-muted font-medium">
          {archetype}
        </span>
      )}
    </div>
  );
}
