import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';

describe('JobHunterClient', () => {
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

  describe('constructor', () => {
    it('uses provided baseUrl', () => {
      const c = new JobHunterClient('http://custom:9090');
      expect((c as any).baseUrl).toBe('http://custom:9090');
    });

    it('defaults to env variable', () => {
      const original = process.env.JOBHUNTER_API_URL;
      process.env.JOBHUNTER_API_URL = 'http://env-url:3000';
      const c = new JobHunterClient();
      expect((c as any).baseUrl).toBe('http://env-url:3000');
      process.env.JOBHUNTER_API_URL = original;
    });

    it('defaults to localhost:8080', () => {
      const original = process.env.JOBHUNTER_API_URL;
      delete process.env.JOBHUNTER_API_URL;
      const c = new JobHunterClient();
      expect((c as any).baseUrl).toBe('http://localhost:8080');
      process.env.JOBHUNTER_API_URL = original;
    });
  });

  describe('searchJobs', () => {
    it('calls GET /api/jobs with query params', async () => {
      const mockData = [{ id: '1', title: 'Engineer' }];
      const spy = mockFetch(mockData);

      const result = await client.searchJobs({ query: 'react', limit: 5 });

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs?query=react&limit=5',
        expect.objectContaining({ headers: expect.objectContaining({ 'Content-Type': 'application/json' }) }),
      );
      expect(result).toEqual(mockData);
    });

    it('calls GET /api/jobs without params when empty', async () => {
      const spy = mockFetch([]);
      await client.searchJobs({});
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs',
        expect.anything(),
      );
    });
  });

  describe('getJob', () => {
    it('calls GET /api/jobs/:id', async () => {
      const mockJob = { id: 'abc', title: 'Dev' };
      const spy = mockFetch(mockJob);

      const result = await client.getJob('abc');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs/abc',
        expect.anything(),
      );
      expect(result).toEqual(mockJob);
    });
  });

  describe('getTechStack', () => {
    it('calls GET /api/jobs/:id/tech-stack', async () => {
      const mockStack = { languages: [{ name: 'TypeScript', required: true }] };
      const spy = mockFetch(mockStack);

      const result = await client.getTechStack('job-1');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs/job-1/tech-stack',
        expect.anything(),
      );
      expect(result).toEqual(mockStack);
    });
  });

  describe('scoreJob', () => {
    it('calls GET /api/jobs/:id/score', async () => {
      const mockScore = { matchPercentage: 85, recommendation: 'apply' };
      const spy = mockFetch(mockScore);

      const result = await client.scoreJob('job-2');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs/job-2/score',
        expect.anything(),
      );
      expect(result).toEqual(mockScore);
    });
  });

  describe('tailorResume', () => {
    it('calls POST /api/tailor/:jobId with body', async () => {
      const mockResult = { summary: 'tailored' };
      const spy = mockFetch(mockResult);

      const result = await client.tailorResume({
        job_id: 'j1',
        emphasis: ['React'],
        format: 'json',
      });

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/tailor/j1',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ emphasis: ['React'], format: 'json' }),
        }),
      );
      expect(result).toEqual(mockResult);
    });
  });

  describe('generateCoverLetter', () => {
    it('calls POST /api/cover-letter/:jobId', async () => {
      const mockLetter = { content: 'Dear...' };
      const spy = mockFetch(mockLetter);

      await client.generateCoverLetter({ job_id: 'j2', tone: 'concise' });

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/cover-letter/j2',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ tone: 'concise' }),
        }),
      );
    });
  });

  describe('markApplied', () => {
    it('calls POST /api/pipeline/:jobId/apply', async () => {
      const spy = mockFetch({ status: 'APPLIED' });

      await client.markApplied({ job_id: 'j3', resume_variant: 'v2', notes: 'test' });

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/pipeline/j3/apply',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ resume_variant: 'v2', notes: 'test' }),
        }),
      );
    });
  });

  describe('recordOutcome', () => {
    it('calls PUT /api/pipeline/:appId/outcome', async () => {
      const spy = mockFetch({ success: true });

      await client.recordOutcome('app-1', 'OFFER', 'Great news');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/pipeline/app-1/outcome',
        expect.objectContaining({
          method: 'PUT',
          body: JSON.stringify({ outcome: 'OFFER', notes: 'Great news' }),
        }),
      );
    });
  });

  describe('getPipeline', () => {
    it('calls GET /api/pipeline with optional status', async () => {
      const spy = mockFetch([]);

      await client.getPipeline('APPLIED');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/pipeline?status=APPLIED',
        expect.anything(),
      );
    });

    it('calls GET /api/pipeline without status', async () => {
      const spy = mockFetch([]);

      await client.getPipeline();

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/pipeline',
        expect.anything(),
      );
    });
  });

  describe('getDailyDigest', () => {
    it('calls GET /api/jobs/daily-digest', async () => {
      const spy = mockFetch({ newJobs: 5 });
      await client.getDailyDigest();
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs/daily-digest',
        expect.anything(),
      );
    });
  });

  describe('getRadar', () => {
    it('calls GET /api/jobs/radar', async () => {
      const spy = mockFetch({ topOpportunities: [] });
      await client.getRadar();
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/jobs/radar',
        expect.anything(),
      );
    });
  });

  describe('listCompanies', () => {
    it('calls GET /api/companies with params', async () => {
      const spy = mockFetch([]);
      await client.listCompanies({ status: 'ACTIVE', sort: 'name' });
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/companies?status=ACTIVE&sort=name',
        expect.anything(),
      );
    });
  });

  describe('getProfile', () => {
    it('calls GET /api/profile', async () => {
      const spy = mockFetch({ name: 'Dev' });
      await client.getProfile();
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/profile',
        expect.anything(),
      );
    });
  });

  describe('getDiscoveryStats', () => {
    it('calls GET /api/discovery/stats', async () => {
      const spy = mockFetch({ discovered: 10 });
      await client.getDiscoveryStats();
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/discovery/stats',
        expect.anything(),
      );
    });
  });

  describe('getSourceQuality', () => {
    it('calls GET /api/discovery/source-quality', async () => {
      const spy = mockFetch({ sources: [] });
      await client.getSourceQuality();
      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/discovery/source-quality',
        expect.anything(),
      );
    });
  });

  describe('addCompany', () => {
    it('calls POST /api/companies with body', async () => {
      const spy = mockFetch({ id: 'new-co' });

      await client.addCompany('https://example.com/careers', 'ExampleCo');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/companies',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ careers_url: 'https://example.com/careers', company_name: 'ExampleCo' }),
        }),
      );
    });

    it('sends undefined company_name when not provided', async () => {
      const spy = mockFetch({ id: 'new-co' });

      await client.addCompany('https://example.com/careers');

      expect(spy).toHaveBeenCalledWith(
        'http://test-api:8080/api/companies',
        expect.objectContaining({
          method: 'POST',
          body: JSON.stringify({ careers_url: 'https://example.com/careers' }),
        }),
      );
    });
  });

  describe('error handling', () => {
    it('throws on non-2xx response', async () => {
      vi.spyOn(globalThis, 'fetch').mockResolvedValue({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        text: () => Promise.resolve('Job not found'),
      } as Response);

      await expect(client.getJob('missing')).rejects.toThrow('API error 404: Not Found - Job not found');
    });

    it('throws on network error', async () => {
      vi.spyOn(globalThis, 'fetch').mockRejectedValue(new Error('Connection refused'));

      await expect(client.getJob('any')).rejects.toThrow('Connection refused');
    });
  });
});
