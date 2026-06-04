// Enums matching Spring Boot backend

export type AtsType = 'GREENHOUSE' | 'LEVER' | 'LEVER_EU' | 'ASHBY' | 'WORKDAY' | 'WORKDAY_PROTECTED' | 'STEPSTONE' | 'UNKNOWN';
export type CompanyStatus = 'DISCOVERED' | 'PENDING_DETECTION' | 'ACTIVE' | 'PROTECTED' | 'UNSUPPORTED' | 'PAUSED';
export type ApplicationStatus = 'INTERESTED' | 'APPLIED' | 'PHONE_SCREEN' | 'INTERVIEWING' | 'OFFERED' | 'REJECTED' | 'WITHDRAWN';
export type Recommendation = 'APPLY' | 'MAYBE' | 'SKIP';
export type SkillCategory = 'LANGUAGE' | 'FRAMEWORK' | 'DATABASE' | 'CLOUD' | 'TOOL' | 'METHODOLOGY' | 'SOFT_SKILL';
export type DiscoverySource = 'MANUAL' | 'STEPSTONE' | 'LINKEDIN_ALERT' | 'JOBSPY';
export type DiscoveryOutcome = 'REGISTERED' | 'ALREADY_EXISTS' | 'DETECTION_FAILED' | 'UNSUPPORTED_ATS' | 'NEW_ENDPOINT_ADDED';
export type RemoteType = 'REMOTE' | 'HYBRID' | 'ONSITE' | null;
export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERNSHIP';
export type SalaryPeriod = 'YEARLY' | 'MONTHLY' | 'HOURLY';

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
  topSkills: string[];
  matchScore: number;
  opportunityScore: number;
  recommendation?: Recommendation | null;
  source: AtsType;
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
