interface VisaBadgeProps {
  status: 'CONFIRMED' | 'LIKELY';
}

export default function VisaBadge({ status }: VisaBadgeProps) {
  const config = {
    CONFIRMED: {
      label: 'Visa ✓',
      tooltip: 'Visa sponsorship confirmed',
      style: 'bg-emerald-500/10 text-emerald-400 ring-emerald-500/20',
    },
    LIKELY: {
      label: 'Visa ~',
      tooltip: 'Visa sponsorship likely',
      style: 'bg-yellow-500/10 text-yellow-400 ring-yellow-500/20',
    },
  };

  const { label, tooltip, style } = config[status];

  return (
    <span
      className={`text-xs px-2 py-0.5 rounded-full ring-1 ${style}`}
      title={tooltip}
    >
      {label}
    </span>
  );
}
