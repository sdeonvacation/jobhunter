import { NavLink } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { fetchApi } from '../api/client';

const links = [
  {
    to: '/digest',
    label: 'Daily Digest',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="3" width="14" height="14" rx="2" />
        <path d="M3 8h14" />
        <path d="M8 3v14" />
      </svg>
    ),
  },
  {
    to: '/today',
    label: 'Today',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="10" cy="10" r="7" />
        <path d="M10 6v4l3 2" />
      </svg>
    ),
  },
  {
    to: '/jobs',
    label: 'Jobs',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="9" cy="9" r="6" />
        <path d="M13.5 13.5L17 17" />
      </svg>
    ),
  },
  {
    to: '/applied',
    label: 'Applied',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="4 10 8 14 16 6" />
      </svg>
    ),
  },
  {
    to: '/follow-ups',
    label: 'Follow-ups',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="10" cy="10" r="7" />
        <path d="M10 6v4" />
        <path d="M10 14h.01" />
      </svg>
    ),
  },
  {
    to: '/analytics',
    label: 'Analytics',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 16V10" />
        <path d="M8 16V6" />
        <path d="M12 16V8" />
        <path d="M16 16V4" />
      </svg>
    ),
  },
  {
    to: '/story-bank',
    label: 'Story Bank',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M4 4h12a2 2 0 012 2v10a2 2 0 01-2 2H4a2 2 0 01-2-2V6a2 2 0 012-2z" />
        <path d="M8 2v4" />
        <path d="M12 2v4" />
        <path d="M6 10h8" />
        <path d="M6 14h4" />
      </svg>
    ),
  },
  {
    to: '/companies',
    label: 'Companies',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="3" y="4" width="14" height="14" rx="1" />
        <path d="M7 4V2h6v2" />
        <path d="M3 9h14" />
        <path d="M8 9v3h4V9" />
      </svg>
    ),
  },
  {
    to: '/people',
    label: 'People',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="10" cy="7" r="3" />
        <path d="M5 17v-1a5 5 0 0110 0v1" />
      </svg>
    ),
  },
  {
    to: '/health',
    label: 'Health',
    icon: (
      <svg width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M3 10h3l2-5 4 10 2-5h3" />
      </svg>
    ),
  },
];

interface HealthSummary {
  totalEndpoints: number;
}

export default function Navigation() {
  const [totalEndpoints, setTotalEndpoints] = useState<number | null>(null);

  useEffect(() => {
    fetchApi<HealthSummary>('/api/admin/health')
      .then((data) => setTotalEndpoints(data.totalEndpoints))
      .catch(() => {});
  }, []);

  return (
    <nav className="w-60 bg-surface-800 border-r border-surface-600 flex flex-col min-h-screen">
      <div className="p-5">
        <h1 className="text-xl font-bold text-text-primary tracking-tight">
          <span className="bg-gradient-to-r from-accent to-accent-light bg-clip-text text-transparent">
            JobHunter
          </span>
        </h1>
        <div className="mt-1.5 h-0.5 w-10 bg-gradient-to-r from-accent to-accent-light rounded-full opacity-80" />
      </div>

      <ul className="flex-1 px-3 py-2 space-y-0.5">
        {links.map((link) => (
          <li key={link.to}>
            <NavLink
              to={link.to}
              className={({ isActive }) =>
                `group flex items-center gap-3 px-3 py-2.5 text-sm rounded-md transition-all duration-150 ease-out-expo ${
                  isActive
                    ? 'bg-accent/10 text-accent border-l-2 border-accent pl-[10px] shadow-glow'
                    : 'text-text-secondary hover:text-text-primary hover:bg-surface-700/50 border-l-2 border-transparent pl-[10px]'
                }`
              }
            >
              <span className="shrink-0 transition-transform duration-150 group-hover:scale-110">
                {link.icon}
              </span>
              <span className="font-medium">{link.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>

      <div className="p-4 border-t border-surface-600">
        <p className="text-xs text-text-muted">
          {totalEndpoints !== null ? `${totalEndpoints} endpoints tracked` : '...'}
        </p>
      </div>
    </nav>
  );
}
