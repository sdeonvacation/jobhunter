import { useState, useEffect, useRef } from 'react';

interface StatsCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  trend?: 'up' | 'down' | 'neutral';
  variant?: 'default' | 'success' | 'danger' | 'warning' | 'muted';
}

export default function StatsCard({ title, value, subtitle, trend, variant = 'default' }: StatsCardProps) {
  const trendIcon = trend === 'up' ? '↑' : trend === 'down' ? '↓' : null;
  const trendColor =
    trend === 'up' ? 'text-success' : trend === 'down' ? 'text-danger' : '';

  const displayValue = useCountUp(value);

  const gradientColors: Record<string, string> = {
    default: 'from-accent to-accent-light',
    success: 'from-success to-emerald-400',
    danger: 'from-danger to-red-400',
    warning: 'from-warning to-amber-400',
    muted: 'from-surface-500 to-surface-500',
  };

  return (
    <div className="bg-surface-800 border border-surface-600 rounded-lg p-5 relative overflow-hidden animate-fade-in">
      {/* Accent gradient line at top with shimmer */}
      <div className="absolute top-0 left-0 right-0 h-0.5 overflow-hidden">
        <div className={`h-full w-full bg-gradient-to-r ${gradientColors[variant]}`} />
        <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/20 to-transparent animate-shimmer" />
      </div>

      <p className="text-text-muted text-xs uppercase tracking-wider font-medium">
        {title}
      </p>
      <p className="text-3xl font-bold text-text-primary font-mono mt-2">
        {displayValue}
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

/** Animate numeric values from 0 to target on mount */
function useCountUp(target: string | number): string | number {
  const numericTarget = typeof target === 'number' ? target : parseInt(target, 10);
  const isNumeric = typeof target === 'number' || !isNaN(numericTarget);
  const [current, setCurrent] = useState(0);
  const rafRef = useRef<number>(0);

  useEffect(() => {
    if (!isNumeric) return;
    const end = numericTarget;
    if (end === 0) { setCurrent(0); return; }

    const duration = 400;
    const start = performance.now();

    const step = (now: number) => {
      const elapsed = now - start;
      const progress = Math.min(elapsed / duration, 1);
      // Ease out cubic
      const eased = 1 - Math.pow(1 - progress, 3);
      setCurrent(Math.round(eased * end));
      if (progress < 1) {
        rafRef.current = requestAnimationFrame(step);
      }
    };

    rafRef.current = requestAnimationFrame(step);
    return () => cancelAnimationFrame(rafRef.current);
  }, [numericTarget, isNumeric]);

  if (!isNumeric) return target;
  return current;
}
