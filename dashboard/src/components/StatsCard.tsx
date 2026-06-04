interface StatsCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  trend?: 'up' | 'down' | 'neutral';
}

export default function StatsCard({ title, value, subtitle, trend }: StatsCardProps) {
  const trendIcon = trend === 'up' ? '↑' : trend === 'down' ? '↓' : null;
  const trendColor =
    trend === 'up' ? 'text-success' : trend === 'down' ? 'text-danger' : '';

  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-5 relative overflow-hidden">
      {/* Accent gradient line at top */}
      <div className="absolute top-0 left-0 right-0 h-0.5 bg-gradient-to-r from-accent to-accent-light" />

      <p className="text-text-muted text-xs uppercase tracking-wider font-medium">
        {title}
      </p>
      <p className="text-3xl font-bold text-text-primary font-mono mt-2">
        {value}
        {trendIcon && (
          <span className={`ml-2 text-sm ${trendColor}`}>{trendIcon}</span>
        )}
      </p>
      {subtitle && (
        <p className="text-xs text-text-muted mt-1.5">{subtitle}</p>
      )}
    </div>
  );
}
