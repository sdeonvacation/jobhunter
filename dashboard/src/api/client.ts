import type {
  Job,
  Company,
  Application,
  ApplicationStatus,
  DailyDigest,
  DiscoveryEvent,
  DiscoveryStats,
  SourceQuality,
  JobSearchParams,
  PageResponse,
  PersonalProfile,
  JobSkill,
  OpportunityScore,
} from '../types';

const API_URL = import.meta.env.VITE_API_URL || '';

class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function fetchApi<T>(path: string, options?: RequestInit): Promise<T> {
  const url = `${API_URL}${path}`;
  const response = await fetch(url, {
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
    ...options,
  });

  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new ApiError(response.status, body || `HTTP ${response.status}`);
  }

  if (response.status === 204) return undefined as T;
  return response.json();
}

function toQueryString(params: Record<string, unknown>): string {
  const entries = Object.entries(params).filter(
    ([, v]) => v !== undefined && v !== null && v !== '',
  );
  if (entries.length === 0) return '';
  return '?' + new URLSearchParams(entries.map(([k, v]) => [k, String(v)])).toString();
}

export const api = {
  jobs: {
    search(params: JobSearchParams): Promise<PageResponse<Job>> {
      return fetchApi(`/api/jobs${toQueryString(params as unknown as Record<string, unknown>)}`);
    },
    getApplied(page = 0, size = 20): Promise<PageResponse<Job>> {
      return fetchApi(`/api/jobs/applied?page=${page}&size=${size}`);
    },
    getToday(page = 0, size = 50, sort = 'matchScore'): Promise<PageResponse<Job>> {
      return fetchApi(`/api/jobs/today?page=${page}&size=${size}&sort=${sort}`);
    },
    markApplied(id: string, applied = true): Promise<void> {
      return fetchApi(`/api/jobs/${id}/applied`, {
        method: 'PATCH',
        body: JSON.stringify({ applied }),
      });
    },
    getById(id: string): Promise<Job> {
      return fetchApi(`/api/jobs/${id}`);
    },
    getTechStack(id: string): Promise<JobSkill[]> {
      return fetchApi(`/api/jobs/${id}/skills`);
    },
    getScore(id: string): Promise<OpportunityScore> {
      return fetchApi(`/api/jobs/${id}/opportunity-score`);
    },
    getDailyDigest(): Promise<DailyDigest> {
      return fetchApi('/api/digest');
    },
    getRadar(): Promise<Job[]> {
      return fetchApi('/api/jobs/radar');
    },
  },

  companies: {
    list(status?: string): Promise<Company[]> {
      const qs = status ? `?status=${status}&size=100` : '?size=100';
      return fetchApi<PageResponse<Company>>(`/api/companies${qs}`).then(r => r.content);
    },
    getById(id: string): Promise<Company> {
      return fetchApi(`/api/companies/${id}`);
    },
    add(data: { name: string; domain?: string }): Promise<Company> {
      return fetchApi('/api/companies', {
        method: 'POST',
        body: JSON.stringify(data),
      });
    },
  },

  pipeline: {
    list(status?: ApplicationStatus): Promise<Application[]> {
      const qs = status ? `?status=${status}` : '';
      return fetchApi(`/api/pipeline${qs}`);
    },
    apply(jobId: string, data?: { notes?: string; resumeVariant?: string }): Promise<Application> {
      return fetchApi(`/api/pipeline/${jobId}/apply`, {
        method: 'POST',
        body: JSON.stringify(data ?? {}),
      });
    },
    updateStatus(id: string, status: ApplicationStatus, notes?: string): Promise<Application> {
      return fetchApi(`/api/pipeline/${id}/outcome`, {
        method: 'PUT',
        body: JSON.stringify({ stage: status, notes }),
      });
    },
    recordOutcome(id: string, outcome: { stage: string; notes?: string }): Promise<Application> {
      return fetchApi(`/api/pipeline/${id}/outcome`, {
        method: 'PUT',
        body: JSON.stringify(outcome),
      });
    },
  },

  discovery: {
    getStats(): Promise<DiscoveryStats> {
      return fetchApi('/api/discovery/stats');
    },
    getSourceQuality(): Promise<SourceQuality[]> {
      return fetchApi('/api/discovery/source-quality');
    },
    getEvents(page = 0, size = 20): Promise<PageResponse<DiscoveryEvent>> {
      return fetchApi(`/api/discovery/events?page=${page}&size=${size}`);
    },
  },

  profile: {
    get(): Promise<PersonalProfile> {
      return fetchApi('/api/profile');
    },
    update(data: Partial<PersonalProfile>): Promise<PersonalProfile> {
      return fetchApi('/api/profile', {
        method: 'PUT',
        body: JSON.stringify(data),
      });
    },
  },
};

export { ApiError, fetchApi };
