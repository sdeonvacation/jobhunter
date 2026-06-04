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

  const strokeColor =
    score >= 70 ? '#10b981' : score >= 40 ? '#f59e0b' : '#ef4444';

  const sizeClass =
    size === 'sm'
      ? 'text-xs px-2 py-0.5 gap-1'
      : 'text-sm px-2.5 py-1 gap-1.5';

  // SVG ring params
  const radius = size === 'sm' ? 6 : 8;
  const circumference = 2 * Math.PI * radius;
  const progress = Math.min(score, 100) / 100;
  const dashOffset = circumference * (1 - progress);
  const svgSize = (radius + 2) * 2;

  return (
    <span
      className={`inline-flex items-center rounded-full ring-1 font-mono font-medium ${color} ${sizeClass}`}
    >
      {label && (
        <span className="text-text-muted text-[10px] uppercase tracking-wide font-sans">
          {label}
        </span>
      )}
      <span className="relative inline-flex items-center gap-1">
        <svg
          width={svgSize}
          height={svgSize}
          className="shrink-0 -rotate-90"
        >
          <circle
            cx={svgSize / 2}
            cy={svgSize / 2}
            r={radius}
            fill="none"
            stroke="currentColor"
            strokeWidth="1.5"
            opacity="0.15"
          />
          <circle
            cx={svgSize / 2}
            cy={svgSize / 2}
            r={radius}
            fill="none"
            stroke={strokeColor}
            strokeWidth="1.5"
            strokeDasharray={circumference}
            strokeDashoffset={dashOffset}
            strokeLinecap="round"
            className="transition-all duration-500 ease-out"
          />
        </svg>
        <span>{score}</span>
      </span>
    </span>
  );
}
