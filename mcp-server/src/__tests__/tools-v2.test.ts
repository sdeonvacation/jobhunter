import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import {
  getJobKeywordsTool,
  markJobAppliedTool,
  getTopJobsTodayTool,
  getTopJobsTool,
  getJobsTool,
  addCompanyTool,
} from '../tools/index.js';

describe('Tool definitions', () => {
  const allTools = [
    getJobKeywordsTool,
    markJobAppliedTool,
    getTopJobsTodayTool,
    getTopJobsTool,
    getJobsTool,
    addCompanyTool,
  ];

  it('exports exactly 6 tools', () => {
    expect(allTools).toHaveLength(6);
  });

  it('all tools have required properties', () => {
    for (const tool of allTools) {
      expect(tool.name).toBeDefined();
      expect(tool.description).toBeDefined();
      expect(tool.inputSchema).toBeDefined();
      expect(tool.handler).toBeDefined();
      expect(typeof tool.name).toBe('string');
      expect(typeof tool.description).toBe('string');
      expect(typeof tool.handler).toBe('function');
    }
  });

  it('all tool names are unique', () => {
    const names = allTools.map((t) => t.name);
    expect(new Set(names).size).toBe(names.length);
  });

  it('tool names match expected values', () => {
    const expected = [
      'get_job_keywords',
      'mark_job_applied',
      'get_top_jobs_today',
      'get_top_jobs',
      'get_jobs',
      'add_company',
    ];
    const names = allTools.map((t) => t.name);
    expect(names.sort()).toEqual(expected.sort());
  });
});

describe('Tool handlers', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test:8080');
    vi.restoreAllMocks();
  });

  function mockFetch(data: unknown) {
    return vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: () => Promise.resolve(data),
      text: () => Promise.resolve(JSON.stringify(data)),
    } as Response);
  }

  function mockFetchSequence(responses: unknown[]) {
    const mock = vi.spyOn(globalThis, 'fetch');
    for (const data of responses) {
      mock.mockResolvedValueOnce({
        ok: true,
        status: 200,
        statusText: 'OK',
        json: () => Promise.resolve(data),
        text: () => Promise.resolve(JSON.stringify(data)),
      } as Response);
    }
    return mock;
  }

  describe('get_job_keywords', () => {
    it('extracts keywords from job detail and tech stack', async () => {
      const jobDetail = {
        id: 'abc12345-6789',
        title: 'Senior Java Developer',
        companyName: 'ACME Corp',
        description: '<p>We need 5+ years experience with Java, Spring Boot, and Kubernetes.</p>',
      };
      const techStack = {
        languages: ['Java', 'Kotlin'],
        frameworks: ['Spring Boot'],
        databases: ['PostgreSQL'],
        cloud: ['AWS'],
        tools: [],
        concepts: ['microservices'],
      };

      mockFetchSequence([jobDetail, techStack]);

      const result = await getJobKeywordsTool.handler({ job_id: 'abc12345-6789' }, client);

      expect(result.content[0].text).toContain('Senior Java Developer @ ACME Corp');
      expect(result.content[0].text).toContain('Java');
      expect(result.content[0].text).toContain('Spring Boot');
      expect(result.content[0].text).toContain('PostgreSQL');
      expect(result.content[0].text).toContain('5+ years experience');
    });

    it('handles missing tech stack gracefully', async () => {
      const jobDetail = {
        id: 'abc12345-6789',
        title: 'Engineer',
        companyName: 'Co',
        description: '<p>Work with React and TypeScript</p>',
      };

      const mock = vi.spyOn(globalThis, 'fetch');
      mock.mockResolvedValueOnce({
        ok: true,
        json: () => Promise.resolve(jobDetail),
        text: () => Promise.resolve(JSON.stringify(jobDetail)),
      } as Response);
      mock.mockResolvedValueOnce({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        text: () => Promise.resolve(''),
      } as Response);

      const result = await getJobKeywordsTool.handler({ job_id: 'abc12345-6789' }, client);

      expect(result.content[0].text).toContain('Engineer @ Co');
      expect(result.content[0].text).toContain('React');
      expect(result.content[0].text).toContain('TypeScript');
    });

    it('handles empty description', async () => {
      mockFetchSequence([
        { id: 'x', title: 'Role', companyName: 'Co', description: '' },
        { languages: ['Go'], frameworks: [], databases: [], cloud: [], tools: [], concepts: [] },
      ]);

      const result = await getJobKeywordsTool.handler({ job_id: 'x' }, client);

      expect(result.content[0].text).toContain('Role @ Co');
      expect(result.content[0].text).toContain('Go');
    });
  });

  describe('mark_job_applied', () => {
    it('marks job and returns confirmation', async () => {
      mockFetch({});

      const result = await markJobAppliedTool.handler({ job_id: 'a3f2c8d1-0000-0000-0000-000000000000' }, client);

      expect(result.content[0].text).toBe('Job a3f2c8d1 marked as applied.');
    });

    it('sends PATCH request with correct body', async () => {
      const fetchSpy = mockFetch({});

      await markJobAppliedTool.handler({ job_id: 'test-id' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/test-id/applied',
        expect.objectContaining({
          method: 'PATCH',
          body: JSON.stringify({ applied: true }),
        }),
      );
    });
  });

  describe('get_top_jobs_today', () => {
    it('returns formatted job list', async () => {
      const page = {
        content: [
          {
            id: 'a3f2c8d1-1234-5678-9abc-def012345678',
            title: 'Senior Java Dev',
            companyName: 'Delivery Hero',
            location: 'Berlin',
            matchScore: 85,
            opportunityScore: 72,
            recommendation: 'APPLY',
          },
          {
            id: 'b7e4f9a2-1234-5678-9abc-def012345678',
            title: 'Backend Engineer',
            companyName: 'Zalando',
            location: 'Remote',
            matchScore: 78,
            opportunityScore: 65,
            recommendation: 'MAYBE',
          },
        ],
        totalElements: 2,
        totalPages: 1,
        size: 10,
        number: 0,
      };
      mockFetch(page);

      const result = await getTopJobsTodayTool.handler({ n: 10 }, client);
      const text = result.content[0].text;

      expect(text).toContain('1. [a3f2c8d1] Senior Java Dev @ Delivery Hero | Berlin | Match: 85 | Opp: 72 | APPLY');
      expect(text).toContain('2. [b7e4f9a2] Backend Engineer @ Zalando | Remote | Match: 78 | Opp: 65 | MAYBE');
    });

    it('returns no jobs message when empty', async () => {
      mockFetch({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 });

      const result = await getTopJobsTodayTool.handler({ n: 10 }, client);

      expect(result.content[0].text).toBe('No jobs found.');
    });

    it('calls today endpoint with correct params', async () => {
      const fetchSpy = mockFetch({ content: [], totalElements: 0, totalPages: 0, size: 5, number: 0 });

      await getTopJobsTodayTool.handler({ n: 5 }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/today?size=5&sort=matchScore',
        expect.anything(),
      );
    });
  });

  describe('get_top_jobs', () => {
    it('returns formatted job list', async () => {
      const page = {
        content: [
          {
            id: 'c1234567-0000-0000-0000-000000000000',
            title: 'Fullstack Dev',
            companyName: 'SAP',
            location: 'Walldorf',
            matchScore: 90,
            opportunityScore: 80,
            recommendation: 'APPLY',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        size: 10,
        number: 0,
      };
      mockFetch(page);

      const result = await getTopJobsTool.handler({ n: 10 }, client);

      expect(result.content[0].text).toContain('[c1234567] Fullstack Dev @ SAP | Walldorf | Match: 90 | Opp: 80 | APPLY');
    });

    it('calls search endpoint without query', async () => {
      const fetchSpy = mockFetch({ content: [], totalElements: 0, totalPages: 0, size: 10, number: 0 });

      await getTopJobsTool.handler({ n: 10 }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs?size=10&sort=matchScore',
        expect.anything(),
      );
    });
  });

  describe('get_jobs', () => {
    it('searches by skill and returns formatted list', async () => {
      const page = {
        content: [
          {
            id: 'd1234567-0000-0000-0000-000000000000',
            title: 'React Developer',
            companyName: 'N26',
            location: 'Berlin',
            matchScore: 75,
            opportunityScore: 60,
            recommendation: 'MAYBE',
          },
        ],
        totalElements: 1,
        totalPages: 1,
        size: 10,
        number: 0,
      };
      mockFetch(page);

      const result = await getJobsTool.handler({ skill: 'react', n: 10 }, client);

      expect(result.content[0].text).toContain('[d1234567] React Developer @ N26');
    });

    it('calls search endpoint with query param', async () => {
      const fetchSpy = mockFetch({ content: [], totalElements: 0, totalPages: 0, size: 5, number: 0 });

      await getJobsTool.handler({ skill: 'kotlin', n: 5 }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs?query=kotlin&size=5&sort=matchScore',
        expect.anything(),
      );
    });
  });

  describe('add_company', () => {
    it('adds company and returns confirmation', async () => {
      mockFetch({ id: 'new-1', name: 'NewCo', status: 'DISCOVERED' });

      const result = await addCompanyTool.handler(
        { name: 'NewCo', careers_url: 'https://newco.com/careers' },
        client,
      );

      expect(result.content[0].text).toContain('Company "NewCo" added');
    });

    it('sends POST with correct body', async () => {
      const fetchSpy = mockFetch({ id: 'x' });

      await addCompanyTool.handler(
        { name: 'ACME', careers_url: 'https://acme.com/jobs' },
        client,
      );

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/companies',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ careers_url: 'https://acme.com/jobs', company_name: 'ACME' }),
        }),
      );
    });
  });
});

describe('Tool input schema validation', () => {
  it('get_job_keywords requires job_id', () => {
    expect(getJobKeywordsTool.inputSchema.safeParse({}).success).toBe(false);
    expect(getJobKeywordsTool.inputSchema.safeParse({ job_id: 'abc' }).success).toBe(true);
  });

  it('mark_job_applied requires job_id', () => {
    expect(markJobAppliedTool.inputSchema.safeParse({}).success).toBe(false);
    expect(markJobAppliedTool.inputSchema.safeParse({ job_id: 'abc' }).success).toBe(true);
  });

  it('get_top_jobs_today defaults n to 10', () => {
    const result = getTopJobsTodayTool.inputSchema.parse({});
    expect(result.n).toBe(10);
  });

  it('get_top_jobs defaults n to 10', () => {
    const result = getTopJobsTool.inputSchema.parse({});
    expect(result.n).toBe(10);
  });

  it('get_jobs requires skill', () => {
    expect(getJobsTool.inputSchema.safeParse({}).success).toBe(false);
    expect(getJobsTool.inputSchema.safeParse({ skill: 'java' }).success).toBe(true);
  });

  it('get_jobs defaults n to 10', () => {
    const result = getJobsTool.inputSchema.parse({ skill: 'java' });
    expect(result.n).toBe(10);
  });

  it('add_company requires name and careers_url', () => {
    expect(addCompanyTool.inputSchema.safeParse({}).success).toBe(false);
    expect(addCompanyTool.inputSchema.safeParse({ name: 'X' }).success).toBe(false);
    expect(addCompanyTool.inputSchema.safeParse({ careers_url: 'x' }).success).toBe(false);
    expect(addCompanyTool.inputSchema.safeParse({ name: 'X', careers_url: 'https://x.com' }).success).toBe(true);
  });
});
