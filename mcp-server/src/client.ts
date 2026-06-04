export interface SearchJobsParams {
  query?: string;
  company?: string;
  location?: string;
  min_score?: number;
  source?: string;
  sort?: string;
  limit?: number;
}

export interface TailorResumeParams {
  job_id: string;
  emphasis?: string[];
  format?: 'json' | 'pdf';
}

export interface GenerateCoverLetterParams {
  job_id: string;
  tone?: 'professional' | 'enthusiastic' | 'concise';
  focus?: string;
}

export interface MarkAppliedParams {
  job_id: string;
  resume_variant?: string;
  notes?: string;
}

export interface ListCompaniesParams {
  status?: 'ACTIVE' | 'DISCOVERED' | 'PAUSED';
  sort?: 'priority' | 'name' | 'interviewRate';
}

export class JobHubClient {
  private baseUrl: string;

  constructor(baseUrl?: string) {
    this.baseUrl = baseUrl || process.env.JOBHUB_API_URL || 'http://localhost:8080';
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

  private buildQuery(params: object): string {
    const entries = Object.entries(params).filter(([, v]) => v !== undefined && v !== null);
    if (entries.length === 0) return '';
    const search = new URLSearchParams();
    for (const [key, value] of entries) {
      search.set(key, String(value));
    }
    return `?${search.toString()}`;
  }

  async searchJobs(params: SearchJobsParams): Promise<unknown> {
    const apiParams: Record<string, string> = {};
    if (params.query) apiParams.query = params.query;
    if (params.company) apiParams.company = params.company;
    if (params.location) apiParams.location = params.location;
    if (params.min_score !== undefined) apiParams.minScore = String(params.min_score);
    if (params.source) apiParams.source = params.source;
    if (params.sort) apiParams.sort = params.sort;
    if (params.limit) apiParams.size = String(params.limit);
    const query = Object.keys(apiParams).length > 0
      ? '?' + new URLSearchParams(apiParams).toString()
      : '';
    return this.request(`/api/jobs${query}`);
  }

  async getJob(id: string): Promise<unknown> {
    return this.request(`/api/jobs/${id}`);
  }

  async getTechStack(jobId: string): Promise<unknown> {
    return this.request(`/api/jobs/${jobId}/tech-stack`);
  }

  async scoreJob(id: string): Promise<unknown> {
    return this.request(`/api/jobs/${id}/score`);
  }

  async tailorResume(params: TailorResumeParams): Promise<unknown> {
    const { job_id, ...body } = params;
    return this.request(`/api/tailor/${job_id}`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  }

  async generateCoverLetter(params: GenerateCoverLetterParams): Promise<unknown> {
    const { job_id, ...body } = params;
    return this.request(`/api/cover-letter/${job_id}`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  }

  async markApplied(params: MarkAppliedParams): Promise<unknown> {
    const { job_id, ...body } = params;
    return this.request(`/api/pipeline/${job_id}/apply`, {
      method: 'POST',
      body: JSON.stringify(body),
    });
  }

  async recordOutcome(applicationId: string, outcome: string, notes?: string): Promise<unknown> {
    return this.request(`/api/pipeline/${applicationId}/outcome`, {
      method: 'PUT',
      body: JSON.stringify({ outcome, notes }),
    });
  }

  async getPipeline(status?: string): Promise<unknown> {
    const query = status ? this.buildQuery({ status }) : '';
    return this.request(`/api/pipeline${query}`);
  }

  async getDailyDigest(): Promise<unknown> {
    return this.request('/api/jobs/daily-digest');
  }

  async getRadar(): Promise<unknown> {
    return this.request('/api/jobs/radar');
  }

  async listCompanies(params?: ListCompaniesParams): Promise<unknown> {
    const query = params ? this.buildQuery(params) : '';
    return this.request(`/api/companies${query}`);
  }

  async getProfile(): Promise<unknown> {
    return this.request('/api/profile');
  }

  async getDiscoveryStats(): Promise<unknown> {
    return this.request('/api/discovery/stats');
  }

  async getSourceQuality(): Promise<unknown> {
    return this.request('/api/discovery/source-quality');
  }

  async addCompany(careersUrl: string, companyName?: string): Promise<unknown> {
    return this.request('/api/companies', {
      method: 'POST',
      body: JSON.stringify({ careers_url: careersUrl, company_name: companyName }),
    });
  }
}
