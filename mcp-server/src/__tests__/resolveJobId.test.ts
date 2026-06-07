import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import { getJobKeywordsTool } from '../tools/getJobKeywords.js';
import { markJobAppliedTool } from '../tools/markJobApplied.js';

describe('JobHunterClient.resolveJobId', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test-api:8080');
    vi.restoreAllMocks();
  });

  function mockFetch(data: unknown, status = 200) {
    return vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      statusText: status === 200 ? 'OK' : 'Error',
      json: () => Promise.resolve(data),
      text: () => Promise.resolve(JSON.stringify(data)),
    } as Response);
  }

  it('returns full UUID as-is without API call', async () => {
    const spy = vi.spyOn(globalThis, 'fetch');
    const fullId = 'a3f2c8d1-1234-5678-9abc-def012345678';

    const result = await client.resolveJobId(fullId);

    expect(result).toBe(fullId);
    expect(spy).not.toHaveBeenCalled();
  });

  it('calls resolve endpoint for short prefix', async () => {
    const fullId = 'a3f2c8d1-1234-5678-9abc-def012345678';
    const spy = mockFetch({ id: fullId });

    const result = await client.resolveJobId('a3f2c8d1');

    expect(spy).toHaveBeenCalledWith(
      'http://test-api:8080/api/jobs/resolve/a3f2c8d1',
      expect.anything(),
    );
    expect(result).toBe(fullId);
  });

  it('throws on resolve failure', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
      text: () => Promise.resolve(''),
    } as Response);

    await expect(client.resolveJobId('deadbeef')).rejects.toThrow('API error 404');
  });

  it('does not short-circuit string of length 36 without dashes', async () => {
    // 36 chars but no dashes — not a full UUID, should call resolve
    const noDashes = 'a3f2c8d112345678abcdef0123456789ab';
    const fullId = 'a3f2c8d1-1234-5678-abcd-ef0123456789';
    const spy = mockFetch({ id: fullId });

    const result = await client.resolveJobId(noDashes);

    expect(spy).toHaveBeenCalledWith(
      `http://test-api:8080/api/jobs/resolve/${noDashes}`,
      expect.anything(),
    );
    expect(result).toBe(fullId);
  });
});

describe('getJobKeywordsTool resolves prefix', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test-api:8080');
    vi.restoreAllMocks();
  });

  it('resolves short ID before fetching job details', async () => {
    const fullId = 'a3f2c8d1-1234-5678-9abc-def012345678';

    vi.spyOn(client, 'resolveJobId').mockResolvedValue(fullId);
    vi.spyOn(client, 'getJob').mockResolvedValue({
      id: fullId,
      title: 'Backend Dev',
      companyName: 'Acme',
      description: '<p>We need Java and Spring Boot experience</p>',
    });
    vi.spyOn(client, 'getTechStack').mockResolvedValue({
      languages: ['Java'],
      frameworks: ['Spring Boot'],
    });

    const result = await getJobKeywordsTool.handler({ job_id: 'a3f2c8d1' }, client);

    expect(client.resolveJobId).toHaveBeenCalledWith('a3f2c8d1');
    expect(client.getJob).toHaveBeenCalledWith(fullId);
    expect(client.getTechStack).toHaveBeenCalledWith(fullId);
    expect(result.content[0].text).toContain('Backend Dev @ Acme');
    expect(result.content[0].text).toContain('Java');
  });
});

describe('markJobAppliedTool resolves prefix', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test-api:8080');
    vi.restoreAllMocks();
  });

  it('resolves short ID before marking applied', async () => {
    const fullId = 'a3f2c8d1-1234-5678-9abc-def012345678';

    vi.spyOn(client, 'resolveJobId').mockResolvedValue(fullId);
    vi.spyOn(client, 'markApplied').mockResolvedValue(undefined);

    const result = await markJobAppliedTool.handler({ job_id: 'a3f2c8d1' }, client);

    expect(client.resolveJobId).toHaveBeenCalledWith('a3f2c8d1');
    expect(client.markApplied).toHaveBeenCalledWith(fullId);
    expect(result.content[0].text).toContain('a3f2c8d1');
    expect(result.content[0].text).toContain('marked as applied');
  });

  it('passes full UUID through resolveJobId', async () => {
    const fullId = 'a3f2c8d1-1234-5678-9abc-def012345678';

    vi.spyOn(client, 'resolveJobId').mockResolvedValue(fullId);
    vi.spyOn(client, 'markApplied').mockResolvedValue(undefined);

    await markJobAppliedTool.handler({ job_id: fullId }, client);

    expect(client.resolveJobId).toHaveBeenCalledWith(fullId);
    expect(client.markApplied).toHaveBeenCalledWith(fullId);
  });
});

describe('addCompany field names', () => {
  let client: JobHunterClient;

  beforeEach(() => {
    client = new JobHunterClient('http://test-api:8080');
    vi.restoreAllMocks();
  });

  it('sends correct field names matching Java record', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      json: () => Promise.resolve({ id: 'new-co' }),
      text: () => Promise.resolve(''),
    } as Response);

    await client.addCompany('Acme Corp', 'https://acme.com/careers');

    expect(spy).toHaveBeenCalledWith(
      'http://test-api:8080/api/companies',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ name: 'Acme Corp', careersUrl: 'https://acme.com/careers' }),
      }),
    );
  });
});
