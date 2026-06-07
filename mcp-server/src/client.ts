export interface PageResponse {
  content: JobSummary[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface JobSummary {
  id: string;
  title: string;
  companyName: string;
  location?: string;
  remoteType?: string;
  opportunityScore?: number;
  matchScore?: number;
  recommendation?: string;
  topSkills?: string[];
  salaryMin?: number;
  salaryMax?: number;
  salaryCurrency?: string;
  postedDate?: string;
  source?: string;
  applyUrl?: string;
  applied?: boolean;
}

export interface JobDetail extends JobSummary {
  description?: string;
}

export interface TechStack {
  languages?: string[];
  frameworks?: string[];
  databases?: string[];
  cloud?: string[];
  tools?: string[];
  concepts?: string[];
}

export class JobHunterClient {
  private baseUrl: string;

  constructor(baseUrl?: string) {
    this.baseUrl = baseUrl || process.env.JOBHUNTER_API_URL || 'http://localhost:8080';
  }

  private async request<T>(path: string, options?: RequestInit): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const response = await fetch(url, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        ...options?.headers,
      },
    });

    if (!response.ok) {
      const body = await response.text().catch(() => '');
      throw new Error(`API error ${response.status}: ${response.statusText}${body ? ` - ${body}` : ''}`);
    }

    return response.json() as Promise<T>;
  }

  async searchJobs(params: { query?: string; size?: number; sort?: string; limit?: number }): Promise<PageResponse> {
    const searchParams = new URLSearchParams();
    if (params.query) searchParams.set('query', params.query);
    const size = params.size ?? params.limit;
    if (size) searchParams.set('size', String(size));
    if (params.sort) searchParams.set('sort', params.sort);
    const qs = searchParams.toString();
    return this.request<PageResponse>(`/api/jobs${qs ? `?${qs}` : ''}`);
  }

  async getTodayJobs(params: { size?: number; sort?: string }): Promise<PageResponse> {
    const searchParams = new URLSearchParams();
    if (params.size) searchParams.set('size', String(params.size));
    if (params.sort) searchParams.set('sort', params.sort);
    const qs = searchParams.toString();
    return this.request<PageResponse>(`/api/jobs/today${qs ? `?${qs}` : ''}`);
  }

  async resolveJobId(idOrPrefix: string): Promise<string> {
    if (idOrPrefix.length === 36 && idOrPrefix.includes('-')) {
      return idOrPrefix;
    }
    const result = await this.request<{ id: string }>(`/api/jobs/resolve/${idOrPrefix}`);
    return result.id;
  }

  async getJob(id: string): Promise<JobDetail> {
    return this.request<JobDetail>(`/api/jobs/${id}`);
  }

  async getTechStack(id: string): Promise<TechStack> {
    return this.request<TechStack>(`/api/jobs/${id}/tech-stack`);
  }

  async markApplied(id: string): Promise<void> {
    await this.request(`/api/jobs/${id}/applied`, {
      method: 'PATCH',
      body: JSON.stringify({ applied: true }),
    });
  }

  async addCompany(name: string, careersUrl: string): Promise<unknown> {
    return this.request('/api/companies', {
      method: 'POST',
      body: JSON.stringify({ name, careersUrl }),
    });
  }

  // Resource-compat methods (used by resources/)
  async getDailyDigest(): Promise<unknown> {
    return this.request('/api/jobs/daily-digest');
  }

  async getRadar(): Promise<unknown> {
    return this.request('/api/jobs/radar');
  }

  async getProfile(): Promise<unknown> {
    return this.request('/api/profile');
  }

  // LinkedIn networking methods
  async findLinkedInContacts(companyName: string, titleKeywords: string[], limit: number): Promise<any[]> {
    return this.request<any[]>('/api/linkedin/contacts/search', {
      method: 'POST',
      body: JSON.stringify({ companyName, titleKeywords, limit }),
    });
  }

  async connectWithContact(contactId: string, note: string): Promise<{ status: string; message: string }> {
    return this.request(`/api/linkedin/contacts/${contactId}/connect`, {
      method: 'POST',
      body: JSON.stringify({ note }),
    });
  }

  async sendLinkedInMessage(contactId: string, message: string): Promise<{ status: string; message: string }> {
    return this.request(`/api/linkedin/contacts/${contactId}/message`, {
      method: 'POST',
      body: JSON.stringify({ message }),
    });
  }

  async researchLinkedInProfile(linkedinUrl: string): Promise<any> {
    return this.request(`/api/linkedin/profile?url=${encodeURIComponent(linkedinUrl)}`);
  }

  async getConnectionsRemaining(): Promise<{ remaining: number }> {
    return this.request('/api/linkedin/contacts/remaining');
  }
}
