import { Routes, Route, Navigate } from 'react-router-dom';
import Navigation from './components/Navigation';
import Jobs from './pages/Jobs';
import DailyDigest from './pages/DailyDigest';
import Applied from './pages/Applied';
import Companies from './pages/Companies';
import Health from './pages/Health';
import People from './pages/People';
import ContactDetail from './pages/ContactDetail';
import Today from './pages/Today';
import Evaluate from './pages/Evaluate';
import Analytics from './pages/Analytics';
import FollowUps from './pages/FollowUps';
import InterviewPrep from './pages/InterviewPrep';
import StoryBank from './pages/StoryBank';
import CoverLetter from './pages/CoverLetter';

export default function App() {
  return (
    <div className="flex h-screen bg-surface-900">
      <Navigation />
      <main className="flex-1 overflow-auto bg-surface-900 p-8">
        <div className="max-w-7xl mx-auto">
          <Routes>
            <Route path="/" element={<Navigate to="/digest" replace />} />
            <Route path="/digest" element={<DailyDigest />} />
            <Route path="/today" element={<Today />} />
            <Route path="/jobs" element={<Jobs />} />
            <Route path="/applied" element={<Applied />} />
            <Route path="/companies" element={<Companies />} />
            <Route path="/people" element={<People />} />
            <Route path="/people/:id" element={<ContactDetail />} />
            <Route path="/evaluate/:jobId" element={<Evaluate />} />
            <Route path="/cover-letter/:jobId" element={<CoverLetter />} />
            <Route path="/analytics" element={<Analytics />} />
            <Route path="/follow-ups" element={<FollowUps />} />
            <Route path="/interview-prep/:jobId" element={<InterviewPrep />} />
            <Route path="/story-bank" element={<StoryBank />} />
            <Route path="/health" element={<Health />} />
            <Route path="*" element={<Navigate to="/digest" replace />} />
          </Routes>
        </div>
      </main>
    </div>
  );
}
