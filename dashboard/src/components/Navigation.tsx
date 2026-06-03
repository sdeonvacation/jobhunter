import { NavLink } from 'react-router-dom';

const links = [
  { to: '/jobs', label: 'Jobs', icon: '🔍' },
  { to: '/pipeline', label: 'Pipeline', icon: '📋' },
  { to: '/companies', label: 'Companies', icon: '🏢' },
  { to: '/discovery', label: 'Discovery', icon: '🔬' },
  { to: '/digest', label: 'Digest', icon: '📰' },
];

export default function Navigation() {
  return (
    <nav className="w-56 bg-gray-900 text-white flex flex-col min-h-screen">
      <div className="p-4 text-xl font-bold border-b border-gray-700">
        JobHub
      </div>
      <ul className="flex-1 py-4">
        {links.map((link) => (
          <li key={link.to}>
            <NavLink
              to={link.to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-4 py-3 text-sm transition-colors ${
                  isActive
                    ? 'bg-blue-600 text-white'
                    : 'text-gray-300 hover:bg-gray-800 hover:text-white'
                }`
              }
            >
              <span>{link.icon}</span>
              <span>{link.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
