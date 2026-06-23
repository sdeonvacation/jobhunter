import { describe, it, expect, vi } from 'vitest';

describe('getJobDescriptionTool', () => {
  it('has correct tool metadata', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');
    expect(getJobDescriptionTool.name).toBe('get_job_description');
    expect(getJobDescriptionTool.description).toContain('description');
    expect(getJobDescriptionTool.inputSchema).toBeDefined();
  });

  // --- ID / short-ID path ---

  it('returns stripped description for UUID lookup', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Backend Engineer',
        companyName: 'TestCo',
        description: '<p>We need <strong>Java</strong> &amp; Spring Boot.</p>',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler({ job_id: 'aaaaaaaa' }, mockClient);

    expect(mockClient.resolveJobId).toHaveBeenCalledWith('aaaaaaaa');
    expect(result.content[0].type).toBe('text');
    expect(result.content[0].text).toContain('Backend Engineer @ TestCo');
    expect(result.content[0].text).toContain('Java');
    expect(result.content[0].text).toContain('Spring Boot');
    // HTML tags stripped
    expect(result.content[0].text).not.toContain('<p>');
    expect(result.content[0].text).not.toContain('<strong>');
    // HTML entities decoded
    expect(result.content[0].text).toContain('&');
  });

  it('returns pending message when description is null for ID lookup', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockResolvedValue({
        title: 'SWE',
        companyName: 'NullCo',
        description: null,
      }),
    } as any;

    const result = await getJobDescriptionTool.handler({ job_id: 'aaaaaaaa' }, mockClient);

    expect(result.content[0].text).toBe(
      'Description not available for this job. It may still be pending enrichment.',
    );
  });

  it('returns pending message when description is undefined for ID lookup', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('bbbbbbbb-cccc-dddd-eeee-ffffffffffff'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Dev',
        companyName: 'Co',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler({ job_id: 'bbbbbbbb' }, mockClient);

    expect(result.content[0].text).toBe(
      'Description not available for this job. It may still be pending enrichment.',
    );
  });

  it('propagates resolveJobId exception', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockRejectedValue(new Error('Job not found: xyz12345')),
    } as any;

    await expect(
      getJobDescriptionTool.handler({ job_id: 'xyz12345' }, mockClient),
    ).rejects.toThrow('Job not found: xyz12345');
  });

  it('propagates getJob exception', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'),
      getJob: vi.fn().mockRejectedValue(new Error('404 Not Found')),
    } as any;

    await expect(
      getJobDescriptionTool.handler({ job_id: 'aaaaaaaa' }, mockClient),
    ).rejects.toThrow('404 Not Found');
  });

  // --- URL path ---

  it('returns stripped description for URL lookup', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      getJobByUrl: vi.fn().mockResolvedValue({
        title: 'Staff Engineer',
        companyName: 'UrlCo',
        description: '<ul><li>Kotlin</li><li>PostgreSQL &lt;13&gt;</li></ul>',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler(
      { job_id: 'https://jobs.lever.co/urlco/abc-123' },
      mockClient,
    );

    expect(mockClient.getJobByUrl).toHaveBeenCalledWith('https://jobs.lever.co/urlco/abc-123');
    expect(result.content[0].text).toContain('Staff Engineer @ UrlCo');
    expect(result.content[0].text).toContain('Kotlin');
    expect(result.content[0].text).toContain('PostgreSQL');
    expect(result.content[0].text).not.toContain('<ul>');
  });

  it('returns not-found message when URL not in DB', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      getJobByUrl: vi.fn().mockResolvedValue(null),
    } as any;

    const url = 'https://example.com/jobs/999';
    const result = await getJobDescriptionTool.handler({ job_id: url }, mockClient);

    expect(result.content[0].text).toBe(`No job found with URL: ${url}`);
  });

  it('returns pending message when URL job has null description', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      getJobByUrl: vi.fn().mockResolvedValue({
        title: 'Engineer',
        companyName: 'PendingCo',
        description: null,
      }),
    } as any;

    const result = await getJobDescriptionTool.handler(
      { job_id: 'https://boards.greenhouse.io/pendingco/jobs/1' },
      mockClient,
    );

    expect(result.content[0].text).toBe(
      'Description not available for this job. It may still be pending enrichment.',
    );
  });

  it('returns pending message when URL job has undefined description', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      getJobByUrl: vi.fn().mockResolvedValue({
        title: 'Eng',
        companyName: 'Co',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler(
      { job_id: 'https://example.com/job/42' },
      mockClient,
    );

    expect(result.content[0].text).toBe(
      'Description not available for this job. It may still be pending enrichment.',
    );
  });

  // --- HTML stripping edge cases ---

  it('strips all common HTML entities', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('cccccccc-dddd-eeee-ffff-000000000000'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Dev',
        companyName: 'Co',
        description: '&lt;tag&gt; &amp; &quot;quoted&quot; &#39;apostrophe&#39;',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler({ job_id: 'cccccccc' }, mockClient);
    const text = result.content[0].text;

    expect(text).toContain('<tag>');
    expect(text).toContain('&');
    expect(text).toContain('"quoted"');
    expect(text).toContain("'apostrophe'");
  });

  it('collapses multiple whitespace in description', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('dddddddd-eeee-ffff-0000-111111111111'),
      getJob: vi.fn().mockResolvedValue({
        title: 'Dev',
        companyName: 'Co',
        description: '<p>Word1</p>   <p>Word2</p>\n\n<p>Word3</p>',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler({ job_id: 'dddddddd' }, mockClient);
    const descLine = result.content[0].text.split('\n\n')[1];

    // No consecutive spaces
    expect(descLine).not.toMatch(/\s{2,}/);
    expect(descLine).toContain('Word1');
    expect(descLine).toContain('Word2');
    expect(descLine).toContain('Word3');
  });

  // --- isUrl detection ---

  it('routes http:// URLs to URL path', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      getJobByUrl: vi.fn().mockResolvedValue(null),
    } as any;

    await getJobDescriptionTool.handler({ job_id: 'http://example.com/job/1' }, mockClient);

    expect(mockClient.getJobByUrl).toHaveBeenCalled();
  });

  it('routes non-URL to ID path', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('eeeeeeee-ffff-0000-1111-222222222222'),
      getJob: vi.fn().mockResolvedValue({ title: 'Dev', companyName: 'Co', description: 'text' }),
    } as any;

    await getJobDescriptionTool.handler({ job_id: 'eeeeeeee' }, mockClient);

    expect(mockClient.resolveJobId).toHaveBeenCalledWith('eeeeeeee');
  });

  // --- Return shape ---

  it('content array has exactly one item with type text', async () => {
    const { getJobDescriptionTool } = await import('../tools/getJobDescription.js');

    const mockClient = {
      resolveJobId: vi.fn().mockResolvedValue('ffffffff-0000-1111-2222-333333333333'),
      getJob: vi.fn().mockResolvedValue({
        title: 'SRE',
        companyName: 'Shape Corp',
        description: '<p>hello</p>',
      }),
    } as any;

    const result = await getJobDescriptionTool.handler({ job_id: 'ffffffff' }, mockClient);

    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe('text');
    expect(typeof result.content[0].text).toBe('string');
  });
});
