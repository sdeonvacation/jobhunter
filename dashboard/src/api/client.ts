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
  Contact,
  ContactDetail,
  RelationshipEvent,
  PeopleStats,
  PeoplePage,
  ContactDiscoveryRun,
  GeneratedMessage,
  ScoredAction,
  FunnelData,
  FunnelAnalysis,
  CompanyIntelligence,
  VisaSignals,
  OutreachMessageItem,
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
  const text = await response.text();
  if (!text) return undefined as T;
  return JSON.parse(text);
}

function get<T>(path: string): Promise<T> {
  return fetchApi<T>(`/api${path}`);
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return fetchApi<T>(`/api${path}`, {
    method: 'POST',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
}

function put<T>(path: string, body?: unknown): Promise<T> {
  return fetchApi<T>(`/api${path}`, {
    method: 'PUT',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
}

function toQuery(params: Record<string, string | undefined>): string {
  const entries = Object.entries(params).filter(([, v]) => v !== undefined) as [string, string][];
  if (entries.length === 0) return '';
  return '?' + new URLSearchParams(entries).toString();
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
    hideJob(id: string, hidden = true): Promise<void> {
      return fetchApi(`/api/jobs/${id}/hidden`, {
        method: 'PATCH',
        body: JSON.stringify({ hidden }),
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
    list(params?: { status?: string; search?: string; page?: number; size?: number }): Promise<PageResponse<Company>> {
      const qs = new URLSearchParams();
      if (params?.status) qs.set('status', params.status);
      if (params?.search) qs.set('search', params.search);
      qs.set('page', String(params?.page ?? 0));
      qs.set('size', String(params?.size ?? 20));
      return fetchApi<PageResponse<Company>>(`/api/companies?${qs.toString()}`);
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
    updatePriority(id: string, priority: number): Promise<void> {
      return fetchApi(`/api/companies/${id}/priority`, {
        method: 'PATCH',
        body: JSON.stringify({ priority }),
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

  people: {
    list(params?: { status?: string; company?: string; seniority?: string; sort?: string; page?: number; size?: number }): Promise<PeoplePage> {
      return fetchApi(`/api/people${toQueryString(params as unknown as Record<string, unknown> ?? {})}`);
    },
    getById(id: string): Promise<ContactDetail> {
      return fetchApi(`/api/people/${id}`);
    },
    getEvents(id: string): Promise<RelationshipEvent[]> {
      return fetchApi(`/api/people/${id}/events`);
    },
    recordEvent(id: string, body: { eventType: string; metadata?: Record<string, unknown> }): Promise<RelationshipEvent> {
      return fetchApi(`/api/people/${id}/events`, {
        method: 'POST',
        body: JSON.stringify(body),
      });
    },
    getStats(): Promise<PeopleStats> {
      return fetchApi('/api/people/stats');
    },
    discoverContacts(companyId: string): Promise<ContactDiscoveryRun> {
      return fetchApi(`/api/people/discover/${companyId}`, { method: 'POST' });
    },
    getDiscoveryRuns(companyId?: string): Promise<ContactDiscoveryRun[]> {
      const qs = companyId ? `?companyId=${companyId}` : '';
      return fetchApi(`/api/people/discovery-runs${qs}`);
    },
    getCompanyContacts(companyId: string): Promise<Contact[]> {
      return fetchApi(`/api/companies/${companyId}/contacts`);
    },
    generateMessage(contactId: string, variant: string, jobId?: string): Promise<GeneratedMessage> {
      return post<GeneratedMessage>(`/contacts/${contactId}/generate-message`, { variant, jobId });
    },
    sendMessage(contactId: string, body: { content: string; channel: string; messageType: string }): Promise<OutreachMessageItem> {
      return post<OutreachMessageItem>(`/contacts/${contactId}/messages`, body);
    },
    getMessages(contactId: string): Promise<OutreachMessageItem[]> {
      return get<OutreachMessageItem[]>(`/contacts/${contactId}/messages`);
    },
  },

  actions: {
    getToday(limit?: number): Promise<ScoredAction[]> {
      return get<ScoredAction[]>(`/actions/today${limit ? `?limit=${limit}` : ''}`);
    },
  },

  funnel: {
    get(from?: string, to?: string): Promise<FunnelData> {
      return get<FunnelData>(`/pipeline/funnel${toQuery({ from, to })}`);
    },
    analyze(): Promise<FunnelAnalysis> {
      return post<FunnelAnalysis>('/pipeline/analyze');
    },
  },

  intelligence: {
    getCompany(id: string): Promise<CompanyIntelligence> {
      return get<CompanyIntelligence>(`/companies/${id}/intelligence`);
    },
    enrichCompany(id: string): Promise<unknown> {
      return post<unknown>(`/companies/${id}/enrich`);
    },
    getVisaSignals(id: string): Promise<VisaSignals> {
      return get<VisaSignals>(`/companies/${id}/visa-signals`);
    },
    updateVisaSignal(id: string, signal: string, value: boolean): Promise<VisaSignals> {
      return put<VisaSignals>(`/companies/${id}/visa-signals`, { signal, value });
    },
  },
};

export { ApiError, fetchApi };
