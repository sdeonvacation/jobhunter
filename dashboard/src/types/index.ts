// Enums matching Spring Boot backend

export type AtsType = 'GREENHOUSE' | 'LEVER' | 'LEVER_EU' | 'ASHBY' | 'WORKDAY' | 'WORKDAY_PROTECTED' | 'STEPSTONE' | 'LINKEDIN' | 'INDEED' | 'ARBEITNOW' | 'UNKNOWN';
export type CompanyStatus = 'DISCOVERED' | 'PENDING_DETECTION' | 'ACTIVE' | 'PROTECTED' | 'UNSUPPORTED' | 'PAUSED';
export type ApplicationStatus = 'INTERESTED' | 'APPLIED' | 'PHONE_SCREEN' | 'INTERVIEWING' | 'OFFERED' | 'REJECTED' | 'WITHDRAWN';
export type Recommendation = 'APPLY' | 'MAYBE' | 'SKIP';
export type SkillCategory = 'LANGUAGE' | 'FRAMEWORK' | 'DATABASE' | 'CLOUD' | 'TOOL' | 'METHODOLOGY' | 'SOFT_SKILL';
export type DiscoverySource = 'MANUAL' | 'STEPSTONE' | 'LINKEDIN_ALERT' | 'JOBSPY';
export type DiscoveryOutcome = 'REGISTERED' | 'ALREADY_EXISTS' | 'DETECTION_FAILED' | 'UNSUPPORTED_ATS' | 'NEW_ENDPOINT_ADDED';
export type RemoteType = 'REMOTE' | 'HYBRID' | 'ONSITE' | null;
export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP';
export type SalaryPeriod = 'YEARLY' | 'MONTHLY' | 'HOURLY';
export type VisaSponsorship = 'CONFIRMED' | 'LIKELY' | 'PENDING' | 'REJECTED' | 'UNKNOWN';

// Domain models

export interface JobSkill {
  id: string;
  skillName: string;
  category: SkillCategory;
  isRequired: boolean;
  rawMention?: string;
}

export interface OpportunityScore {
  id: string;
  score: number;
  breakdown: Record<string, number>;
  computedAt: string;
}

export interface MatchScore {
  id: string;
  overallScore: number;
  matchedSkills: string[];
  missingSkills: string[];
  recommendation: Recommendation;
  scoredAt: string;
}

export interface Job {
  id: string;
  title: string;
  companyName: string;
  location?: string;
  locationCity?: string;
  locationCountry?: string;
  remoteType?: RemoteType;
  description?: string;
  applyUrl?: string;
  postedDate?: string;
  discoveredDate?: string;
  employmentType?: EmploymentType;
  salaryMin?: number;
  salaryMax?: number;
  salaryCurrency?: string;
  salaryPeriod?: SalaryPeriod;
  isActive?: boolean;
  applied?: boolean;
  hidden?: boolean;
  topSkills: string[];
  matchScore: number;
  opportunityScore: number;
  recommendation?: Recommendation | null;
  source: AtsType;
  externalLinks?: Record<string, string>;
  visaSponsorship?: VisaSponsorship | null;
  createdAt?: string;
}

export interface CompanySummary {
  id: string;
  name: string;
}

export interface Company {
  id: string;
  name: string;
  normalizedName?: string;
  domain?: string;
  country?: string;
  status: CompanyStatus;
  discoveredVia?: DiscoverySource;
  discoveredAt?: string;
  avgMatchScore?: number;
  interviewRate: number;
  totalApplications: number;
  totalInterviews?: number;
  priorityScore: number;
  endpointCount?: number;
  careerEndpoints?: CareerEndpoint[];
  createdAt?: string;
  updatedAt?: string;
}

export interface CareerEndpoint {
  id: string;
  url: string;
  atsType: AtsType;
  atsSlug?: string;
  verified: boolean;
  isActive: boolean;
  lastCrawledAt?: string;
}

export interface Application {
  id: string;
  job: Job;
  status: ApplicationStatus;
  appliedDate?: string;
  notes?: string;
  resumeVariant?: string;
  createdAt: string;
  updatedAt: string;
}

export interface DiscoveryEvent {
  id: string;
  companyName: string;
  provider: string;
  sourceJobTitle?: string;
  sourceUrl?: string;
  discoveredAt: string;
  outcome: DiscoveryOutcome;
}

export interface DiscoveryStats {
  totalDiscovered: number;
  totalResolved: number;
  activeCompanies: number;
  pendingDetection: number;
  unsupported: number;
}

export interface SourceQuality {
  source: DiscoverySource;
  totalApplications: number;
  totalInterviews: number;
  interviewRate: number;
}

export interface DailyDigest {
  date: string;
  newJobsCount: number;
  skippedCount: number;
  topOpportunityTitle?: string;
  topOpportunityCompany?: string;
  topOpportunityScore: number;
  heatingCompanies: string[];
  coolingCompanies: string[];
  sourceInterviewRates: Record<string, number>;
}

export interface JobSearchParams {
  query?: string;
  location?: string;
  company?: string;
  minScore?: number;
  source?: string;
  sort?: string;
  page?: number;
  size?: number;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface PersonalProfile {
  targetRole: string;
  locations: string[];
  skills: string[];
  minSalary?: number;
  preferredRemote?: RemoteType;
}

// People Module types
export type Seniority = 'RECRUITER' | 'MANAGER' | 'DIRECTOR' | 'STAFF' | 'SENIOR' | 'IC';
export type ContactDiscoverySource = 'JOB_POSTER' | 'LINKEDIN_SEARCH' | 'MANUAL';
export type RelationshipStatus = 'DISCOVERED' | 'CONTACTED' | 'REPLIED' | 'ENGAGED' | 'REFERRED' | 'INTERVIEW_OBTAINED' | 'GHOSTED' | 'COLD';
export type EventType = 'CONTACT_DISCOVERED' | 'MESSAGE_SENT' | 'REPLIED' | 'CALL_BOOKED' | 'REFERRAL_REQUESTED' | 'REFERRAL_GIVEN' | 'INTERVIEW_OBTAINED' | 'GHOSTED_AUTO' | 'STATUS_OVERRIDE';
export type InterviewSource = 'APPLICATION' | 'RECRUITER' | 'REFERRAL' | 'NETWORKING' | 'EVENT';
export type ConnectionStatus = 'NONE' | 'PENDING' | 'CONNECTED' | 'DECLINED';

export interface Contact {
  id: string;
  personName: string;
  title?: string;
  linkedinUrl: string;
  companyId: string;
  companyName: string;
  seniority?: Seniority;
  discoveredVia: ContactDiscoverySource;
  connectionStatus: ConnectionStatus;
  interviewGenerationWeight: number;
  warmthScore: number;
  contactPriorityScore: number;
  relationshipStatus?: RelationshipStatus;
  lastContactAt?: string;
  createdAt: string;
  email?: string;
  emailConfidence?: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface ContactDetail extends Contact {
  location?: string;
  techStack?: string[];
  events: RelationshipEvent[];
  messages: OutreachMessageItem[];
  linkedJobs: LinkedJob[];
  referredBy?: Contact;
}

export interface SuggestedContact {
  id: string;
  personName: string;
  title: string | null;
  seniority: string | null;
  linkedinUrl: string;
  email: string | null;
  emailConfidence: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH';
  contactPriorityScore: number;
}

export interface RelationshipEvent {
  id: string;
  eventType: EventType;
  occurredAt: string;
  metadata?: Record<string, unknown>;
}

export interface OutreachMessageItem {
  id: string;
  direction: 'IN' | 'OUT';
  channel: 'LINKEDIN' | 'EMAIL';
  messageType: string;
  content?: string;
  sentAt: string;
  replied: boolean;
  repliedAt?: string;
}

export interface LinkedJob {
  id: string;
  title: string;
  companyName: string;
  location?: string;
  postedDate?: string;
}

export interface PeopleStats {
  totalContacts: number;
  byStatus: Record<RelationshipStatus, number>;
  bySeniority: Record<Seniority, number>;
  avgPriorityScore: number;
  discoveredToday: number;
}

export interface ContactDiscoveryRun {
  id: string;
  companyId: string;
  companyName: string;
  source: ContactDiscoverySource;
  contactsFound: number;
  contactsNew: number;
  runAt: string;
}

export interface PeoplePage {
  content: Contact[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

// Phase 3-5 types
export type MessageVariant = 'INFO_CHAT' | 'TECH_DISCUSSION' | 'REFERRAL_ASK' | 'FOLLOW_UP' | 'RECRUITER_PITCH';
export type ActionType = 'FOLLOW_UP' | 'CONNECT' | 'APPLY' | 'PREPARE' | 'SEND_MESSAGE';
export type VisaFriendliness = 'UNKNOWN' | 'LOW' | 'MEDIUM' | 'HIGH';

export interface GeneratedMessage {
  content: string;
  variant: MessageVariant;
  contactId: string;
  jobId?: string;
  modelUsed: string;
  tokensUsed: number;
}

export interface ScoredAction {
  entityId: string;
  type: ActionType;
  impactScore: number;
  urgencyScore: number;
  actionScore: number;
  reason: string;
  expiresIn: string;
  contactId?: string;
  jobId?: string;
  contactName?: string;
  companyName: string;
  jobTitle?: string;
}

export interface FunnelData {
  applications: number;
  recruiterScreen: number;
  technical: number;
  finalRound: number;
  offers: number;
  conversionRates: Record<string, number>;
  avgDaysBetweenStages: Record<string, number>;
}

export interface FunnelAnalysis {
  primaryBottleneck: string;
  explanation: string;
  suggestions: string[];
  stageInsights: Record<string, string>;
}

export interface CompanyIntelligence {
  companyId: string;
  industry?: string;
  employeeCount?: number;
  specialties?: string;
  hiringVelocity?: number;
  employeeGrowth?: string;
  fundingStage?: string;
  visaSignals: VisaSignals;
  lastEnrichedAt?: string;
}

export interface VisaSignals {
  hasSponsoredBefore?: boolean;
  englishSpeaking?: boolean;
  internationalWorkforce?: boolean;
  derived: VisaFriendliness;
}
