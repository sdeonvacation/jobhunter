import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import {
  evaluateJobTool,
  generateCoverLetterTool,
  checkJobLivenessTool,
  prepareInterviewTool,
  getApplicationPatternsTool,
  getFollowUpScheduleTool,
} from '../tools/index.js';

describe('Career-ops tool definitions', () => {
  const tools = [
    evaluateJobTool,
    generateCoverLetterTool,
    checkJobLivenessTool,
    prepareInterviewTool,
    getApplicationPatternsTool,
    getFollowUpScheduleTool,
  ];

  it('all tools have required properties', () => {
    for (const tool of tools) {
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
    const names = tools.map((t) => t.name);
    expect(new Set(names).size).toBe(names.length);
  });

  it('tool names match expected values', () => {
    const expected = [
      'evaluate_job',
      'generate_cover_letter',
      'check_job_liveness',
      'prepare_interview',
      'get_application_patterns',
      'get_followup_schedule',
    ];
    const names = tools.map((t) => t.name);
    expect(names.sort()).toEqual(expected.sort());
  });
});

describe('Career-ops tool input schema validation', () => {
  describe('evaluate_job', () => {
    it('requires job_id', () => {
      expect(evaluateJobTool.inputSchema.safeParse({}).success).toBe(false);
      expect(evaluateJobTool.inputSchema.safeParse({ job_id: 'abc' }).success).toBe(true);
    });

    it('accepts optional blocks array', () => {
      const result = evaluateJobTool.inputSchema.safeParse({ job_id: 'abc', blocks: ['skills_gap', 'culture_fit'] });
      expect(result.success).toBe(true);
      expect(result.data?.blocks).toEqual(['skills_gap', 'culture_fit']);
    });

    it('blocks is optional', () => {
      const result = evaluateJobTool.inputSchema.parse({ job_id: 'abc' });
      expect(result.blocks).toBeUndefined();
    });
  });

  describe('generate_cover_letter', () => {
    it('requires job_id', () => {
      expect(generateCoverLetterTool.inputSchema.safeParse({}).success).toBe(false);
      expect(generateCoverLetterTool.inputSchema.safeParse({ job_id: 'x' }).success).toBe(true);
    });

    it('accepts valid tone values', () => {
      expect(generateCoverLetterTool.inputSchema.safeParse({ job_id: 'x', tone: 'professional' }).success).toBe(true);
      expect(generateCoverLetterTool.inputSchema.safeParse({ job_id: 'x', tone: 'enthusiastic' }).success).toBe(true);
      expect(generateCoverLetterTool.inputSchema.safeParse({ job_id: 'x', tone: 'conversational' }).success).toBe(true);
    });

    it('rejects invalid tone', () => {
      expect(generateCoverLetterTool.inputSchema.safeParse({ job_id: 'x', tone: 'aggressive' }).success).toBe(false);
    });

    it('accepts optional focus and angles', () => {
      const result = generateCoverLetterTool.inputSchema.parse({
        job_id: 'x',
        focus: 'backend scalability',
        angles: ['open source contributions', 'team leadership'],
      });
      expect(result.focus).toBe('backend scalability');
      expect(result.angles).toEqual(['open source contributions', 'team leadership']);
    });
  });

  describe('check_job_liveness', () => {
    it('requires job_id', () => {
      expect(checkJobLivenessTool.inputSchema.safeParse({}).success).toBe(false);
      expect(checkJobLivenessTool.inputSchema.safeParse({ job_id: 'abc' }).success).toBe(true);
    });
  });

  describe('prepare_interview', () => {
    it('requires job_id', () => {
      expect(prepareInterviewTool.inputSchema.safeParse({}).success).toBe(false);
      expect(prepareInterviewTool.inputSchema.safeParse({ job_id: 'abc' }).success).toBe(true);
    });
  });

  describe('get_application_patterns', () => {
    it('accepts no params', () => {
      expect(getApplicationPatternsTool.inputSchema.safeParse({}).success).toBe(true);
    });

    it('accepts optional since', () => {
      const result = getApplicationPatternsTool.inputSchema.parse({ since: '2024-01-01' });
      expect(result.since).toBe('2024-01-01');
    });
  });

  describe('get_followup_schedule', () => {
    it('accepts no params', () => {
      expect(getFollowUpScheduleTool.inputSchema.safeParse({}).success).toBe(true);
    });

    it('accepts valid status values', () => {
      expect(getFollowUpScheduleTool.inputSchema.safeParse({ status: 'OVERDUE' }).success).toBe(true);
      expect(getFollowUpScheduleTool.inputSchema.safeParse({ status: 'PENDING' }).success).toBe(true);
      expect(getFollowUpScheduleTool.inputSchema.safeParse({ status: 'ALL' }).success).toBe(true);
    });

    it('rejects invalid status', () => {
      expect(getFollowUpScheduleTool.inputSchema.safeParse({ status: 'DONE' }).success).toBe(false);
    });

    it('accepts optional limit', () => {
      const result = getFollowUpScheduleTool.inputSchema.parse({ limit: 5 });
      expect(result.limit).toBe(5);
    });
  });
});

describe('Career-ops tool handlers', () => {
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

  describe('evaluate_job', () => {
    it('resolves short ID and calls evaluate endpoint', async () => {
      const evaluation = { overallFit: 'STRONG', score: 85, gaps: [] };
      const fetchSpy = mockFetchSequence([
        { id: 'full-uuid-0000-0000-0000-000000000000' }, // resolve
        evaluation, // evaluate POST
      ]);

      const result = await evaluateJobTool.handler({ job_id: 'full-uui' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/full-uuid-0000-0000-0000-000000000000/evaluate',
        expect.objectContaining({ method: 'POST', body: '{}' }),
      );
      expect(result.content[0].text).toContain('"overallFit": "STRONG"');
    });

    it('passes blocks when provided', async () => {
      const evaluation = { overallFit: 'MODERATE', score: 65 };
      const fetchSpy = mockFetchSequence([
        { id: 'full-uuid-0000-0000-0000-000000000000' },
        evaluation,
      ]);

      await evaluateJobTool.handler({ job_id: 'full-uui', blocks: ['skills_gap'] }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/full-uuid-0000-0000-0000-000000000000/evaluate',
        expect.objectContaining({ method: 'POST', body: JSON.stringify({ blocks: ['skills_gap'] }) }),
      );
    });

    it('handles URL-based job lookup', async () => {
      const evaluation = { overallFit: 'STRONG', score: 90 };
      const fetchSpy = mockFetchSequence([
        { id: 'url-job-uuid-0000-0000-000000000000', title: 'Dev', companyName: 'Co' }, // getJobByUrl
        evaluation, // evaluate
      ]);

      const result = await evaluateJobTool.handler({ job_id: 'https://jobs.example.com/123' }, client);

      expect(fetchSpy).toHaveBeenNthCalledWith(
        1,
        'http://test:8080/api/jobs/by-url?url=https%3A%2F%2Fjobs.example.com%2F123',
        expect.anything(),
      );
      expect(result.content[0].text).toContain('"overallFit": "STRONG"');
    });

    it('returns error when URL job not found', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: () => Promise.resolve(null),
        text: () => Promise.resolve(''),
      } as Response);

      const result = await evaluateJobTool.handler({ job_id: 'https://jobs.example.com/missing' }, client);

      expect(result.content[0].text).toContain('No job found with URL');
    });

    it('skips resolve for full UUID', async () => {
      const evaluation = { overallFit: 'WEAK', score: 30 };
      const fetchSpy = mockFetch(evaluation);

      await evaluateJobTool.handler({ job_id: 'a3f2c8d1-1234-5678-9abc-def012345678' }, client);

      // Should call evaluate directly (no resolve call)
      expect(fetchSpy).toHaveBeenCalledTimes(1);
      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/a3f2c8d1-1234-5678-9abc-def012345678/evaluate',
        expect.anything(),
      );
    });
  });

  describe('generate_cover_letter', () => {
    it('calls cover-letter endpoint with params', async () => {
      const coverLetter = { content: 'Dear Hiring Manager...', wordCount: 250 };
      const fetchSpy = mockFetchSequence([
        { id: 'job-uuid-0000-0000-0000-000000000000' },
        coverLetter,
      ]);

      const result = await generateCoverLetterTool.handler(
        { job_id: 'job-uuid', tone: 'enthusiastic', focus: 'backend', angles: ['open source'] },
        client,
      );

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/job-uuid-0000-0000-0000-000000000000/cover-letter',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ tone: 'enthusiastic', focus: 'backend', angles: ['open source'] }),
        }),
      );
      expect(result.content[0].text).toContain('Dear Hiring Manager');
    });

    it('sends empty body when no optional params', async () => {
      const coverLetter = { content: 'Hello...' };
      const fetchSpy = mockFetch(coverLetter);

      await generateCoverLetterTool.handler({ job_id: 'a3f2c8d1-1234-5678-9abc-def012345678' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/a3f2c8d1-1234-5678-9abc-def012345678/cover-letter',
        expect.objectContaining({ method: 'POST', body: '{}' }),
      );
    });
  });

  describe('check_job_liveness', () => {
    it('calls liveness-check endpoint', async () => {
      const liveness = { isLive: true, lastCheckedAt: '2024-06-01T10:00:00Z', httpStatus: 200 };
      const fetchSpy = mockFetch(liveness);

      const result = await checkJobLivenessTool.handler({ job_id: 'a3f2c8d1-1234-5678-9abc-def012345678' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/a3f2c8d1-1234-5678-9abc-def012345678/liveness-check',
        expect.objectContaining({ method: 'POST' }),
      );
      expect(result.content[0].text).toContain('"isLive": true');
    });

    it('resolves short ID before calling', async () => {
      const fetchSpy = mockFetchSequence([
        { id: 'resolved-0000-0000-0000-000000000000' },
        { isLive: false, httpStatus: 404 },
      ]);

      const result = await checkJobLivenessTool.handler({ job_id: 'resolved' }, client);

      expect(fetchSpy).toHaveBeenNthCalledWith(
        1,
        'http://test:8080/api/jobs/resolve/resolved',
        expect.anything(),
      );
      expect(result.content[0].text).toContain('"isLive": false');
    });
  });

  describe('prepare_interview', () => {
    it('calls interview-prep endpoint', async () => {
      const prep = { questions: ['Tell me about...'], topics: ['System design'], company: 'ACME' };
      const fetchSpy = mockFetch(prep);

      const result = await prepareInterviewTool.handler({ job_id: 'a3f2c8d1-1234-5678-9abc-def012345678' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/a3f2c8d1-1234-5678-9abc-def012345678/interview-prep',
        expect.objectContaining({ method: 'POST' }),
      );
      expect(result.content[0].text).toContain('Tell me about');
      expect(result.content[0].text).toContain('System design');
    });
  });

  describe('get_application_patterns', () => {
    it('calls patterns endpoint without params', async () => {
      const patterns = { totalApplications: 42, responseRate: 0.35 };
      const fetchSpy = mockFetch(patterns);

      const result = await getApplicationPatternsTool.handler({}, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/analytics/patterns',
        expect.anything(),
      );
      expect(result.content[0].text).toContain('"totalApplications": 42');
    });

    it('passes since param as query string', async () => {
      const patterns = { totalApplications: 10 };
      const fetchSpy = mockFetch(patterns);

      await getApplicationPatternsTool.handler({ since: '2024-03-01' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/analytics/patterns?since=2024-03-01',
        expect.anything(),
      );
    });
  });

  describe('get_followup_schedule', () => {
    it('calls follow-ups endpoint without params', async () => {
      const schedule = { items: [{ jobId: 'abc', dueDate: '2024-06-15' }] };
      const fetchSpy = mockFetch(schedule);

      const result = await getFollowUpScheduleTool.handler({}, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/follow-ups',
        expect.anything(),
      );
      expect(result.content[0].text).toContain('"dueDate": "2024-06-15"');
    });

    it('passes status and limit as query params', async () => {
      const schedule = { items: [] };
      const fetchSpy = mockFetch(schedule);

      await getFollowUpScheduleTool.handler({ status: 'OVERDUE', limit: 5 }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/follow-ups?status=OVERDUE&limit=5',
        expect.anything(),
      );
    });

    it('passes only status when limit not provided', async () => {
      const schedule = { items: [] };
      const fetchSpy = mockFetch(schedule);

      await getFollowUpScheduleTool.handler({ status: 'PENDING' }, client);

      expect(fetchSpy).toHaveBeenCalledWith(
        'http://test:8080/api/follow-ups?status=PENDING',
        expect.anything(),
      );
    });
  });
});
