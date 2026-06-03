import { Routes, Route, Navigate } from 'react-router-dom';
import Navigation from './components/Navigation';
import Jobs from './pages/Jobs';
import Pipeline from './pages/Pipeline';
import Companies from './pages/Companies';
import Discovery from './pages/Discovery';
import Digest from './pages/Digest';

export default function App() {
  return (
    <div className="flex h-screen">
      <Navigation />
      <main className="flex-1 overflow-auto p-6">
        <Routes>
          <Route path="/" element={<Navigate to="/jobs" replace />} />
          <Route path="/jobs" element={<Jobs />} />
          <Route path="/pipeline" element={<Pipeline />} />
          <Route path="/companies" element={<Companies />} />
          <Route path="/discovery" element={<Discovery />} />
          <Route path="/digest" element={<Digest />} />
        </Routes>
      </main>
    </div>
  );
}
