import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHubClient } from '../client.js';
import { applyWithKeywordsTool } from '../tools/applyWithKeywords.js';

describe('applyWithKeywords tool', () => {
  let client: JobHubClient;

  beforeEach(() => {
    client = new JobHubClient('http://test:8080');
    vi.restoreAllMocks();
  });

  function mockFetchSequence(responses: Array<{ ok: boolean; data?: unknown; status?: number }>) {
    const mock = vi.spyOn(globalThis, 'fetch');
    for (const resp of responses) {
      mock.mockResolvedValueOnce({
        ok: resp.ok,
        status: resp.status || (resp.ok ? 200 : 404),
        statusText: resp.ok ? 'OK' : 'Not Found',
        json: () => Promise.resolve(resp.data),
        text: () => Promise.resolve(JSON.stringify(resp.data || '')),
      } as Response);
    }
    return mock;
  }

  it('has correct name and description', () => {
    expect(applyWithKeywordsTool.name).toBe('apply_with_keywords');
    expect(applyWithKeywordsTool.description).toContain('Mark multiple jobs as applied');
  });

  it('requires job_ids array', () => {
    const invalid = applyWithKeywordsTool.inputSchema.safeParse({});
    expect(invalid.success).toBe(false);

    const valid = applyWithKeywordsTool.inputSchema.safeParse({ job_ids: ['abc123'] });
    expect(valid.success).toBe(true);
  });

  it('notes is optional', () => {
    const withNotes = applyWithKeywordsTool.inputSchema.safeParse({
      job_ids: ['id1'],
      notes: 'Some note',
    });
    expect(withNotes.success).toBe(true);

    const without = applyWithKeywordsTool.inputSchema.safeParse({ job_ids: ['id1'] });
    expect(without.success).toBe(true);
  });

  it('applies single job and extracts keywords', async () => {
    const jobDetail = {
      id: 'abcdef12-3456-7890-abcd-ef1234567890',
      title: 'Backend Engineer',
      companyName: 'TestCorp',
      location: 'Berlin',
      description: '<p>We need a Java and Spring Boot developer with 5+ years experience</p>',
      opportunityScore: 80,
      matchScore: 72,
    };
    const techStack = {
      languages: ['Java', 'Kotlin'],
      frameworks: ['Spring Boot'],
    };

    mockFetchSequence([
      { ok: true, data: jobDetail },   // getJob
      { ok: true, data: techStack },    // getTechStack
      { ok: true, data: { status: 'APPLIED' } }, // markApplied
    ]);

    const result = await applyWithKeywordsTool.handler(
      { job_ids: ['abcdef12-3456-7890-abcd-ef1234567890'] },
      client,
    );

    expect(result.content[0].type).toBe('text');
    const text = result.content[0].text;
    expect(text).toContain('Applied to 1 job(s)');
    expect(text).toContain('[abcdef12]');
    expect(text).toContain('Backend Engineer @ TestCorp');
    expect(text).toContain('APPLIED ✓');
    expect(text).toContain('Java');
    expect(text).toContain('Kotlin');
    expect(text).toContain('Spring Boot');
  });

  it('handles job not found gracefully', async () => {
    mockFetchSequence([
      { ok: false, status: 404 },  // getJob fails
      { ok: false, status: 404 },  // getTechStack fails
    ]);

    const result = await applyWithKeywordsTool.handler(
      { job_ids: ['deadbeef'] },
      client,
    );

    const text = result.content[0].text;
    expect(text).toContain('[deadbeef]');
    expect(text).toContain('NOT FOUND');
  });

  it('handles multiple jobs in batch', async () => {
    const job1 = {
      id: '11111111-0000-0000-0000-000000000000',
      title: 'Frontend Dev',
      companyName: 'Alpha',
      location: 'Remote',
      description: 'React and TypeScript required',
      opportunityScore: 70,
      matchScore: 65,
    };
    const job2 = {
      id: '22222222-0000-0000-0000-000000000000',
      title: 'Backend Dev',
      companyName: 'Beta',
      location: 'Munich',
      description: 'Kubernetes and Docker expertise',
      opportunityScore: 85,
      matchScore: 80,
    };

    mockFetchSequence([
      { ok: true, data: job1 },     // getJob 1
      { ok: true, data: null },     // getTechStack 1
      { ok: true, data: {} },       // markApplied 1
      { ok: true, data: job2 },     // getJob 2
      { ok: true, data: { cloud: ['Kubernetes'] } }, // getTechStack 2
      { ok: true, data: {} },       // markApplied 2
    ]);

    const result = await applyWithKeywordsTool.handler(
      { job_ids: ['11111111', '22222222'], notes: 'Batch apply' },
      client,
    );

    const text = result.content[0].text;
    expect(text).toContain('Applied to 2 job(s)');
    expect(text).toContain('[11111111]');
    expect(text).toContain('Frontend Dev @ Alpha');
    expect(text).toContain('[22222222]');
    expect(text).toContain('Backend Dev @ Beta');
    expect(text).toContain('React');
    expect(text).toContain('TypeScript');
    expect(text).toContain('Kubernetes');
    expect(text).toContain('Docker');
  });

  it('handles markApplied failure gracefully', async () => {
    const job = {
      id: 'aaaabbbb-0000-0000-0000-000000000000',
      title: 'Ops Engineer',
      companyName: 'Gamma',
      description: 'AWS required',
      opportunityScore: 60,
      matchScore: 50,
    };

    mockFetchSequence([
      { ok: true, data: job },      // getJob
      { ok: true, data: null },     // getTechStack
      { ok: false, status: 500 },   // markApplied fails
    ]);

    const result = await applyWithKeywordsTool.handler(
      { job_ids: ['aaaabbbb'] },
      client,
    );

    const text = result.content[0].text;
    expect(text).toContain('[aaaabbbb]');
    expect(text).toContain('ERROR');
  });

  it('extracts YOE and degree keywords from description', async () => {
    const job = {
      id: 'ccccdddd-0000-0000-0000-000000000000',
      title: 'Senior Dev',
      companyName: 'Delta',
      description: 'Requires Master degree and 3+ years of experience in Python',
      opportunityScore: 90,
      matchScore: 85,
    };

    mockFetchSequence([
      { ok: true, data: job },
      { ok: true, data: null },
      { ok: true, data: {} },
    ]);

    const result = await applyWithKeywordsTool.handler(
      { job_ids: ['ccccdddd'] },
      client,
    );

    const text = result.content[0].text;
    expect(text).toContain('Python');
    expect(text).toContain('Master');
    expect(text).toContain('3+ years experience');
  });

  it('deduplicates keywords from tech stack and description', async () => {
    const job = {
      id: 'eeeeffff-0000-0000-0000-000000000000',
      title: 'Fullstack',
      companyName: 'Echo',
      description: 'Java and PostgreSQL. We use Java extensively.',
      opportunityScore: 75,
      matchScore: 70,
    };
    const techStack = {
      languages: ['Java'],
      databases: ['PostgreSQL'],
    };

    mockFetchSequence([
      { ok: true, data: job },
      { ok: true, data: techStack },
      { ok: true, data: {} },
    ]);

    const result = await applyWithKeywordsTool.handler(
      { job_ids: ['eeeeffff'] },
      client,
    );

    const text = result.content[0].text;
    // Keywords should be deduplicated
    const keywordsLine = text.split('\n').find((l: string) => l.startsWith('Keywords:'));
    expect(keywordsLine).toBeDefined();
    const javaMatches = keywordsLine!.split('Java').length - 1;
    // "Java" should appear at most once (tech stack takes priority, JD doesn't duplicate)
    expect(javaMatches).toBeLessThanOrEqual(1);
  });
});
