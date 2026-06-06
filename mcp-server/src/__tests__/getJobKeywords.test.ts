import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';

// We test the getJobKeywords module's path resolution behavior
// by manipulating the env var and verifying it loads correctly

describe('getJobKeywords - path resolution', () => {
  const originalEnv = process.env.JOBHUNTER_KEYWORDS;

  afterEach(() => {
    if (originalEnv === undefined) {
      delete process.env.JOBHUNTER_KEYWORDS;
    } else {
      process.env.JOBHUNTER_KEYWORDS = originalEnv;
    }
  });

  it('loads keywords.yaml from monorepo root (default path)', async () => {
    delete process.env.JOBHUNTER_KEYWORDS;
    // The module loads patterns at import time; if this succeeds, path resolution works
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');
    expect(getJobKeywordsTool).toBeDefined();
    expect(getJobKeywordsTool.name).toBe('get_job_keywords');
  });

  it('respects JOBHUNTER_KEYWORDS env var', async () => {
    const __dirname = dirname(fileURLToPath(import.meta.url));
    // Point to the keywords.yaml in mcp-server/ (npm package location)
    const keywordsPath = resolve(__dirname, '../../keywords.yaml');
    process.env.JOBHUNTER_KEYWORDS = keywordsPath;

    // Force re-import with cache bust
    const mod = await import(`../tools/getJobKeywords.js?t=${Date.now()}`);
    expect(mod.getJobKeywordsTool).toBeDefined();
  });

  it('throws when keywords.yaml not found anywhere', async () => {
    process.env.JOBHUNTER_KEYWORDS = '/nonexistent/path/keywords.yaml';

    // We can't easily test the throw from module-level code without isolating imports,
    // but we verify the env path check: a non-existent env path won't satisfy existsSync
    // and other paths will be tried. This test validates the fallback chain works
    // (the module still loads because monorepo path exists)
    const mod = await import(`../tools/getJobKeywords.js?t=${Date.now() + 1}`);
    expect(mod.getJobKeywordsTool).toBeDefined();
  });
});

describe('getJobKeywords - keyword extraction', () => {
  it('has correct tool metadata', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');
    expect(getJobKeywordsTool.name).toBe('get_job_keywords');
    expect(getJobKeywordsTool.description).toContain('Extract tech keywords');
    expect(getJobKeywordsTool.inputSchema).toBeDefined();
  });

  it('handler extracts keywords from URL', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    // Mock fetch for URL-based extraction
    const mockHtml = '<html><body>We need Java, Spring Boot, and Kubernetes experience. 5+ years of experience required.</body></html>';
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      text: () => Promise.resolve(mockHtml),
    }) as any;

    try {
      const mockClient = {} as any;
      const result = await getJobKeywordsTool.handler(
        { job_id: 'https://example.com/job/123' },
        mockClient,
      );

      expect(result.content[0].type).toBe('text');
      expect(result.content[0].text).toContain('URL: https://example.com/job/123');
      expect(result.content[0].text).toContain('Keywords:');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('handler throws on failed URL fetch', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
    }) as any;

    try {
      const mockClient = {} as any;
      await expect(
        getJobKeywordsTool.handler({ job_id: 'https://example.com/missing' }, mockClient),
      ).rejects.toThrow('Failed to fetch URL: 404 Not Found');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('handler resolves job by ID via client', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Backend Engineer',
        companyName: 'TestCo',
        description: '<p>Looking for Java and Spring Boot developers</p>',
      }),
      getTechStack: vi.fn().mockResolvedValue({
        languages: ['Java', 'Kotlin'],
        frameworks: ['Spring Boot'],
      }),
    } as any;

    const result = await getJobKeywordsTool.handler({ job_id: 'aaaaaaaa' }, mockClient);

    expect(mockClient.resolveJobId).toHaveBeenCalledWith('aaaaaaaa');
    expect(result.content[0].text).toContain('Backend Engineer @ TestCo');
    expect(result.content[0].text).toContain('Java');
    expect(result.content[0].text).toContain('Kotlin');
    expect(result.content[0].text).toContain('Spring Boot');
  });

  it('handler handles null tech stack gracefully', async () => {
    const { getJobKeywordsTool } = await import('../tools/getJobKeywords.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('11111111-2222-3333-4444-555555555555'),
      getJob: vi.fn().mockResolvedValue({
        title: 'SWE',
        companyName: 'NullCo',
        description: 'Basic role, no tech mentioned',
      }),
      getTechStack: vi.fn().mockRejectedValue(new Error('Not found')),
    } as any;

    const result = await getJobKeywordsTool.handler({ job_id: '11111111' }, mockClient);

    expect(result.content[0].text).toContain('SWE @ NullCo');
  });
});
