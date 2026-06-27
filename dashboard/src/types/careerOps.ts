// Career Ops feature types

export type LegitimacyTier = 'GREEN' | 'AMBER' | 'RED';
export type LivenessStatus = 'ACTIVE' | 'EXPIRED' | 'UNCERTAIN';
export type FollowUpStatus = 'OVERDUE' | 'PENDING' | 'SENT' | 'SKIPPED';
export type CoverLetterTone = 'PROFESSIONAL' | 'CONVERSATIONAL' | 'ENTHUSIASTIC';

export interface EvaluationBlock {
  [key: string]: unknown;
}

export interface EvaluationResult {
  jobId: string;
  jobTitle: string;
  companyName: string;
  overallScore: number;
  archetype: string;
  legitimacyTier: LegitimacyTier;
  roleSummary: EvaluationBlock;
  cvMatch: EvaluationBlock;
  levelStrategy: EvaluationBlock;
  compResearch: EvaluationBlock;
  customizationPlan: EvaluationBlock;
  interviewPlan: EvaluationBlock;
  legitimacy: EvaluationBlock;
  descriptionFingerprint: string;
  evaluatedAt: string;
}

export interface CoverLetterResult {
  id: string;
  content: string;
  tone: CoverLetterTone;
  version: number;
  generatedAt: string;
}

export interface LivenessResult {
  status: LivenessStatus;
  checkedAt: string;
  url?: string;
  reason?: string;
}

export interface InterviewPrepResult {
  talkingPoints: string[];
  mappedStoryIds: string[];
  companyResearch: string;
}

export interface FunnelMetrics {
  totalEvaluated: number;
  applied: number;
  responded: number;
  interviewing: number;
  offered: number;
  rejected: number;
  applicationRate: number;
  responseRate: number;
  interviewRate: number;
  offerRate: number;
}

export interface PatternAnalytics {
  funnel: FunnelMetrics;
  scoreComparison: {
    avgScorePositiveOutcome: number;
    avgScoreNegativeOutcome: number;
    positiveCount: number;
    negativeCount: number;
  };
  blockerAnalysis: { reason: string; count: number }[];
  techStackGaps: { skill: string; count: number }[];
  scoreThreshold: number;
  archetypeByCompany: Record<string, number>;
  archetypeByRemoteType: Record<string, number>;
}

export interface FollowUpItem {
  id: string;
  jobId: string;
  jobTitle: string;
  companyName: string;
  scheduledDate: string;
  count: number;
  status: FollowUpStatus;
}

export interface FollowUpSchedule {
  followUps: FollowUpItem[];
  total: number;
  overdueCount: number;
}

export interface InterviewStory {
  id: string;
  situation: string;
  task?: string;
  action: string;
  result: string;
  reflection?: string;
  tags: string[];
  skills: string[];
  createdAt: string;
}
