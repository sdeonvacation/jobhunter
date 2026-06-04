import { Routes, Route, Navigate } from 'react-router-dom';
import Navigation from './components/Navigation';
import Jobs from './pages/Jobs';
import Companies from './pages/Companies';

export default function App() {
  return (
    <div className="flex h-screen bg-surface-900">
      <Navigation />
      <main className="flex-1 overflow-auto bg-surface-900 p-8">
        <div className="max-w-7xl mx-auto">
          <Routes>
            <Route path="/" element={<Navigate to="/jobs" replace />} />
            <Route path="/jobs" element={<Jobs />} />
            <Route path="/companies" element={<Companies />} />
            <Route path="*" element={<Navigate to="/jobs" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
