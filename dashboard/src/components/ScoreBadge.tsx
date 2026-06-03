interface ScoreBadgeProps {
  score: number;
  label?: string;
  size?: 'sm' | 'md';
}

export default function ScoreBadge({ score, label, size = 'md' }: ScoreBadgeProps) {
  const color =
    score >= 70
      ? 'bg-green-100 text-green-800 border-green-300'
      : score >= 50
        ? 'bg-yellow-100 text-yellow-800 border-yellow-300'
        : 'bg-red-100 text-red-800 border-red-300';

  const sizeClass = size === 'sm' ? 'text-xs px-1.5 py-0.5' : 'text-sm px-2 py-1';

  return (
    <span className={`inline-flex items-center gap-1 rounded border font-medium ${color} ${sizeClass}`}>
      {label && <span className="opacity-70">{label}</span>}
      <span>{score}</span>
    </span>
  );
}
