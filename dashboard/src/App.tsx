import { Routes, Route, Navigate } from 'react-router-dom';
import Navigation from './components/Navigation';
import Jobs from './pages/Jobs';
import Pipeline from './pages/Pipeline';
import Companies from './pages/Companies';
import Discovery from './pages/Discovery';
import Digest from './pages/Digest';

export default function App() {
  return (
    <div className="flex h-screen bg-surface-900">
      <Navigation />
      <main className="flex-1 overflow-auto bg-surface-900 p-8">
        <div className="max-w-7xl mx-auto">
          <Routes>
            <Route path="/" element={<Navigate to="/jobs" replace />} />
            <Route path="/jobs" element={<Jobs />} />
            <Route path="/pipeline" element={<Pipeline />} />
            <Route path="/companies" element={<Companies />} />
            <Route path="/discovery" element={<Discovery />} />
            <Route path="/digest" element={<Digest />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
