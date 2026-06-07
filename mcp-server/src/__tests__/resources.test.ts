import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import { profileResources, jobResources } from '../resources/index.js';

describe('Resources', () => {
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

  describe('profileResources', () => {
    it('has 2 resources', () => {
      expect(profileResources).toHaveLength(2);
    });

    it('profile://skills fetches profile and returns skills', async () => {
      const profile = { skills: [{ name: 'TypeScript', level: 'expert' }] };
      mockFetch(profile);

      const skillsResource = profileResources.find((r) => r.uri === 'profile://skills')!;
      const result = await skillsResource.handler(client);

      expect(JSON.parse(result)).toEqual(profile.skills);
    });

    it('profile://skills returns full profile when no skills field', async () => {
      const profile = { name: 'Dev', experience: [] };
      mockFetch(profile);

      const skillsResource = profileResources.find((r) => r.uri === 'profile://skills')!;
      const result = await skillsResource.handler(client);

      expect(JSON.parse(result)).toEqual(profile);
    });

    it('profile://resume fetches profile and returns resume', async () => {
      const profile = { resume: { summary: 'Experienced dev' } };
      mockFetch(profile);

      const resumeResource = profileResources.find((r) => r.uri === 'profile://resume')!;
      const result = await resumeResource.handler(client);

      expect(JSON.parse(result)).toEqual(profile.resume);
    });

    it('all profile resources have required fields', () => {
      for (const r of profileResources) {
        expect(r.uri).toBeDefined();
        expect(r.name).toBeDefined();
        expect(r.description).toBeDefined();
        expect(r.mimeType).toBe('application/json');
        expect(typeof r.handler).toBe('function');
      }
    });
  });

  describe('jobResources', () => {
    it('has 3 resources', () => {
      expect(jobResources).toHaveLength(3);
    });

    it('jobs://top-opportunities fetches top 10 jobs', async () => {
      const jobs = [{ id: '1' }, { id: '2' }];
      const spy = mockFetch(jobs);

      const resource = jobResources.find((r) => r.uri === 'jobs://top-opportunities')!;
      const result = await resource.handler(client);

      expect(spy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs?limit=10',
        expect.anything(),
      );
      expect(JSON.parse(result)).toEqual(jobs);
    });

    it('jobs://daily-digest fetches digest', async () => {
      const digest = { newJobs: 5 };
      const spy = mockFetch(digest);

      const resource = jobResources.find((r) => r.uri === 'jobs://daily-digest')!;
      const result = await resource.handler(client);

      expect(spy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/daily-digest',
        expect.anything(),
      );
      expect(JSON.parse(result)).toEqual(digest);
    });

    it('jobs://radar fetches radar', async () => {
      const radar = { heating: [], cooling: [] };
      const spy = mockFetch(radar);

      const resource = jobResources.find((r) => r.uri === 'jobs://radar')!;
      const result = await resource.handler(client);

      expect(spy).toHaveBeenCalledWith(
        'http://test:8080/api/jobs/radar',
        expect.anything(),
      );
      expect(JSON.parse(result)).toEqual(radar);
    });

    it('all job resources have required fields', () => {
      for (const r of jobResources) {
        expect(r.uri).toBeDefined();
        expect(r.name).toBeDefined();
        expect(r.description).toBeDefined();
        expect(r.mimeType).toBe('application/json');
        expect(typeof r.handler).toBe('function');
      }
    });
  });

  describe('all resources combined', () => {
    it('total of 5 resources', () => {
      expect(profileResources.length + jobResources.length).toBe(5);
    });

    it('all URIs are unique', () => {
      const uris = [...profileResources, ...jobResources].map((r) => r.uri);
      expect(new Set(uris).size).toBe(uris.length);
    });
  });
});
