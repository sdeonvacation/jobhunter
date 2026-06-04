interface ScoreBadgeProps {
  score: number;
  label?: string;
  size?: 'sm' | 'md';
}

export default function ScoreBadge({ score, label, size = 'md' }: ScoreBadgeProps) {
  const color =
    score >= 70
      ? 'text-success ring-success/30 bg-success/10'
      : score >= 40
        ? 'text-warning ring-warning/30 bg-warning/10'
        : 'text-danger ring-danger/30 bg-danger/10';

  const sizeClass =
    size === 'sm'
      ? 'text-xs px-2 py-0.5 gap-1'
      : 'text-sm px-2.5 py-1 gap-1.5';

  return (
    <span
      className={`inline-flex items-center rounded-full ring-1 font-mono font-medium ${color} ${sizeClass}`}
    >
      {label && (
        <span className="text-text-muted text-[10px] uppercase tracking-wide font-sans">
          {label}
        </span>
      )}
      <span>{score}</span>
    </span>
  );
}
