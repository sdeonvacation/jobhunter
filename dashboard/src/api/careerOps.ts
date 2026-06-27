import type {
  EvaluationResult,
  CoverLetterResult,
  CoverLetterTone,
  LivenessResult,
  InterviewPrepResult,
  InterviewStory,
  PatternAnalytics,
  FollowUpSchedule,
  FollowUpStatus,
} from '../types/careerOps';
import { fetchApi } from './client';

export const careerOps = {
  evaluateJob(id: string): Promise<EvaluationResult> {
    return fetchApi<EvaluationResult>(`/api/jobs/${id}/evaluate`, { method: 'POST' });
  },

  getEvaluation(id: string): Promise<EvaluationResult> {
    return fetchApi<EvaluationResult>(`/api/jobs/${id}/evaluation`);
  },

  generateCoverLetter(id: string, opts?: { tone?: CoverLetterTone }): Promise<CoverLetterResult> {
    return fetchApi<CoverLetterResult>(`/api/jobs/${id}/cover-letter`, {
      method: 'POST',
      body: opts ? JSON.stringify(opts) : undefined,
    });
  },

  getCoverLetters(id: string): Promise<CoverLetterResult[]> {
    return fetchApi<CoverLetterResult[]>(`/api/jobs/${id}/cover-letters`);
  },

  getLiveness(id: string): Promise<LivenessResult> {
    return fetchApi<LivenessResult>(`/api/jobs/${id}/liveness`);
  },

  getInterviewPrep(id: string): Promise<InterviewPrepResult> {
    return fetchApi<InterviewPrepResult>(`/api/jobs/${id}/interview-prep`);
  },

  prepareInterview(id: string): Promise<InterviewPrepResult> {
    return fetchApi<InterviewPrepResult>(`/api/jobs/${id}/interview-prep`, { method: 'POST' });
  },

  getPatterns(since?: string): Promise<PatternAnalytics> {
    const qs = since ? `?since=${encodeURIComponent(since)}` : '';
    return fetchApi<PatternAnalytics>(`/api/analytics/patterns${qs}`);
  },

  getFollowUps(status?: FollowUpStatus, limit?: number): Promise<FollowUpSchedule> {
    const params = new URLSearchParams();
    if (status) params.set('status', status);
    if (limit) params.set('limit', String(limit));
    const qs = params.toString() ? `?${params.toString()}` : '';
    return fetchApi<FollowUpSchedule>(`/api/follow-ups${qs}`);
  },

  markFollowUpSent(id: string, notes?: string): Promise<void> {
    return fetchApi<void>(`/api/follow-ups/${id}/sent`, {
      method: 'PATCH',
      body: notes ? JSON.stringify({ notes }) : undefined,
    });
  },

  getStories(): Promise<InterviewStory[]> {
    return fetchApi<InterviewStory[]>('/api/interview-stories');
  },

  addStory(data: Omit<InterviewStory, 'id' | 'createdAt'>): Promise<InterviewStory> {
    return fetchApi<InterviewStory>('/api/interview-stories', {
      method: 'POST',
      body: JSON.stringify(data),
    });
  },

  deleteStory(id: string): Promise<void> {
    return fetchApi<void>(`/api/interview-stories/${id}`, { method: 'DELETE' });
  },

  deleteCoverLetter(jobId: string, coverId: string): Promise<void> {
    return fetchApi<void>(`/api/jobs/${jobId}/cover-letters/${coverId}`, { method: 'DELETE' });
  },
};
