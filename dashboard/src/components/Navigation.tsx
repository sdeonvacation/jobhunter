import { NavLink } from 'react-router-dom';

const links = [
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
];

export default function Navigation() {
  return (
    <nav className="w-60 bg-surface-800 border-r border-surface-600 flex flex-col min-h-screen">
      <div className="p-5">
        <h1 className="text-lg font-bold text-text-primary tracking-tight">
          JobHub
        </h1>
        <div className="mt-1.5 h-0.5 w-10 bg-gradient-to-r from-accent to-accent-light rounded-full" />
      </div>

      <ul className="flex-1 px-3 py-2 space-y-0.5">
        {links.map((link) => (
          <li key={link.to}>
            <NavLink
              to={link.to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 text-sm rounded-md transition-all ${
                  isActive
                    ? 'bg-accent/10 text-accent border-l-2 border-accent pl-[10px]'
                    : 'text-text-secondary hover:text-text-primary hover:bg-surface-700 border-l-2 border-transparent pl-[10px]'
                }`
              }
            >
              <span className="shrink-0">{link.icon}</span>
              <span className="font-medium">{link.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>

      <div className="p-4 border-t border-surface-600">
        <p className="text-xs text-text-muted">547 jobs tracked</p>
      </div>
    </nav>
  );
}
