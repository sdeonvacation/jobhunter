import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

describe('getJobKeywords - LLM extraction', () => {
  let originalFetch: typeof globalThis.fetch;
  let originalEnv: NodeJS.ProcessEnv;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    originalEnv = { ...process.env };
    process.env.JOBHUNTER_AI_BASE_URL = 'https://ai.example.com/v1/chat/completions';
    process.env.JOBHUNTER_AI_API_KEY = 'test-api-key';
    process.env.JOBHUNTER_AI_EXTRACTION_MODEL = 'test-model';
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    process.env = originalEnv;
  });

  it('has correct tool metadata', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');
    expect(getJobKeywordsTool.name).toBe('get_job_keywords');
    expect(getJobKeywordsTool.description).toContain('Extract tech keywords');
    expect(getJobKeywordsTool.inputSchema).toBeDefined();
  });

  it('extractKeywordsViaLLM sends correct payload to LLM API', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () =>
        Promise.resolve({
          choices: [{ message: { content: JSON.stringify({ keywords: ['Java', 'Spring Boot'] }) } }],
        }),
    });
    globalThis.fetch = mockFetch as any;

    const result = await extractKeywordsViaLLM('We need Java and Spring Boot developers');

    expect(result).toEqual(['Java', 'Spring Boot']);
    expect(mockFetch).toHaveBeenCalledTimes(1);

    const [url, options] = mockFetch.mock.calls[0];
    expect(url).toBe('https://ai.example.com/v1/chat/completions');
    expect(options.method).toBe('POST');
    expect(options.headers['Authorization']).toBe('Bearer test-api-key');

    const body = JSON.parse(options.body);
    expect(body.model).toBe('test-model');
    expect(body.messages).toHaveLength(2);
    expect(body.messages[0].role).toBe('system');
    expect(body.messages[1].role).toBe('user');
    expect(body.messages[1].content).toContain('Java and Spring Boot');
    expect(body.response_format.type).toBe('json_schema');
    expect(body.response_format.json_schema.schema.properties.keywords.type).toBe('array');
  });

  it('extractKeywordsViaLLM returns empty array when API key missing', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');
    delete process.env.JOBHUNTER_AI_API_KEY;

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await extractKeywordsViaLLM('some text');

    expect(result).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('Missing'));
    warnSpy.mockRestore();
  });

  it('extractKeywordsViaLLM returns empty array when base URL missing', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');
    delete process.env.JOBHUNTER_AI_BASE_URL;

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await extractKeywordsViaLLM('some text');

    expect(result).toEqual([]);
    warnSpy.mockRestore();
  });

  it('extractKeywordsViaLLM returns empty array on API error', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: 'Internal Server Error',
    }) as any;

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await extractKeywordsViaLLM('some text');

    expect(result).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('500'));
    warnSpy.mockRestore();
  });

  it('extractKeywordsViaLLM returns empty array on network failure', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED')) as any;

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await extractKeywordsViaLLM('some text');

    expect(result).toEqual([]);
    expect(warnSpy).toHaveBeenCalledWith(
      expect.stringContaining('LLM extraction failed'),
      expect.stringContaining('ECONNREFUSED'),
    );
    warnSpy.mockRestore();
  });

  it('extractKeywordsViaLLM returns empty array on malformed JSON response', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ choices: [{ message: { content: 'not json' } }] }),
    }) as any;

    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const result = await extractKeywordsViaLLM('some text');

    expect(result).toEqual([]);
    warnSpy.mockRestore();
  });

  it('extractKeywordsViaLLM uses default model when env not set', async () => {
    const { extractKeywordsViaLLM } = await import('../tools/getJobKeywords.js');
    delete process.env.JOBHUNTER_AI_EXTRACTION_MODEL;

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ choices: [{ message: { content: '{"keywords":["Go"]}' } }] }),
    });
    globalThis.fetch = mockFetch as any;

    await extractKeywordsViaLLM('Go developer needed');

    const body = JSON.parse(mockFetch.mock.calls[0][1].body);
    expect(body.model).toBe('gemini-3.1-flash-lite');
  });

  it('handler extracts keywords from URL via LLM', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockHtml = '<html><body>We need Java, Spring Boot, and Kubernetes experience.</body></html>';
    const llmResponse = { keywords: ['Java', 'Spring Boot', 'Kubernetes'] };

    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url === 'https://example.com/job/123') {
        return Promise.resolve({ ok: true, text: () => Promise.resolve(mockHtml) });
      }
      // LLM API call
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ choices: [{ message: { content: JSON.stringify(llmResponse) } }] }),
      });
    }) as any;

    const mockClient = { getJobByUrl: vi.fn().mockResolvedValue(null) } as any;
    const result = await getJobKeywordsTool.handler({ job_id: 'https://example.com/job/123' }, mockClient);

    expect(result.content[0].type).toBe('text');
    expect(result.content[0].text).toContain('URL: https://example.com/job/123');
    expect(result.content[0].text).toContain('Java');
    expect(result.content[0].text).toContain('Spring Boot');
    expect(result.content[0].text).toContain('Kubernetes');
  });

  it('handler throws on failed URL fetch', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
    }) as any;

    const mockClient = { getJobByUrl: vi.fn().mockResolvedValue(null) } as any;
    await expect(
      getJobKeywordsTool.handler({ job_id: 'https://example.com/missing' }, mockClient),
    ).rejects.toThrow('Failed to fetch URL: 404 Not Found');
  });

  it('handler resolves job by ID and extracts via LLM', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const llmResponse = { keywords: ['Java', 'Kotlin', 'Spring Boot', 'PostgreSQL'] };
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ choices: [{ message: { content: JSON.stringify(llmResponse) } }] }),
    }) as any;

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Backend Engineer',
        companyName: 'TestCo',
        description: '<p>Looking for Java and Spring Boot developers with PostgreSQL</p>',
      }),
    } as any;

    const result = await getJobKeywordsTool.handler({ job_id: 'aaaaaaaa' }, mockClient);

    expect(mockClient.resolveJobId).toHaveBeenCalledWith('aaaaaaaa');
    expect(result.content[0].text).toContain('Backend Engineer @ TestCo');
    expect(result.content[0].text).toContain('Java');
    expect(result.content[0].text).toContain('Spring Boot');
    expect(result.content[0].text).toContain('PostgreSQL');
  });

  it('handler returns "none extracted" when description is empty', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('11111111-2222-3333-4444-555555555555'),
      getJob: vi.fn().mockResolvedValue({
        title: 'SWE',
        companyName: 'NullCo',
        description: '',
      }),
    } as any;

    const result = await getJobKeywordsTool.handler({ job_id: '11111111' }, mockClient);

    expect(result.content[0].text).toContain('SWE @ NullCo');
    expect(result.content[0].text).toContain('none extracted');
  });

  it('handler gracefully handles LLM failure for ID-based extraction', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    globalThis.fetch = vi.fn().mockRejectedValue(new Error('timeout')) as any;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Dev',
        companyName: 'FailCo',
        description: '<p>Some job desc</p>',
      }),
    } as any;

    const result = await getJobKeywordsTool.handler({ job_id: 'aaaaaaaa' }, mockClient);

    expect(result.content[0].text).toContain('Dev @ FailCo');
    expect(result.content[0].text).toContain('none extracted');
    warnSpy.mockRestore();
  });

  it('handler detects ATS type from Greenhouse URL', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockHtml = '<html><body>We need TypeScript developers</body></html>';
    const llmResponse = { keywords: ['TypeScript'] };

    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('greenhouse.io')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve(mockHtml) });
      }
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ choices: [{ message: { content: JSON.stringify(llmResponse) } }] }),
      });
    }) as any;

    const mockClient = { getJobByUrl: vi.fn().mockResolvedValue(null) } as any;
    const result = await getJobKeywordsTool.handler(
      { job_id: 'https://boards.greenhouse.io/acme/jobs/12345' }, mockClient,
    );

    expect(result.content[0].text).toContain('ATS: GREENHOUSE');
    expect(result.content[0].text).toContain('TypeScript');
  });

  it('handler detects ATS type from Lever URL', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockHtml = '<html><body>Python role</body></html>';
    const llmResponse = { keywords: ['Python'] };

    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('lever.co')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve(mockHtml) });
      }
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ choices: [{ message: { content: JSON.stringify(llmResponse) } }] }),
      });
    }) as any;

    const mockClient = { getJobByUrl: vi.fn().mockResolvedValue(null) } as any;
    const result = await getJobKeywordsTool.handler(
      { job_id: 'https://jobs.lever.co/stripe/abc-123' }, mockClient,
    );

    expect(result.content[0].text).toContain('ATS: LEVER');
  });

  it('handler shows no ATS line for unknown URL patterns', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockHtml = '<html><body>Go developers wanted</body></html>';
    const llmResponse = { keywords: ['Go'] };

    globalThis.fetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('randomsite.com')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve(mockHtml) });
      }
      return Promise.resolve({
        ok: true,
        json: () => Promise.resolve({ choices: [{ message: { content: JSON.stringify(llmResponse) } }] }),
      });
    }) as any;

    const mockClient = { getJobByUrl: vi.fn().mockResolvedValue(null) } as any;
    const result = await getJobKeywordsTool.handler(
      { job_id: 'https://randomsite.com/careers/123' }, mockClient,
    );

    expect(result.content[0].text).not.toContain('ATS:');
    expect(result.content[0].text).toContain('Go');
  });

  it('handler includes atsType from API response for ID-based lookup', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const llmResponse = { keywords: ['Java'] };
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({ choices: [{ message: { content: JSON.stringify(llmResponse) } }] }),
    }) as any;

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Engineer',
        companyName: 'AshbyCo',
        description: '<p>Java dev</p>',
        atsType: 'ASHBY',
      }),
    } as any;

    const result = await getJobKeywordsTool.handler({ job_id: 'aaaaaaaa' }, mockClient);

    expect(result.content[0].text).toContain('ATS: ASHBY');
    expect(result.content[0].text).toContain('Engineer @ AshbyCo');
  });
});
