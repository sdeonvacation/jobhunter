import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fetchApi, ApiError, api } from '../api/client';

declare const global: { fetch: typeof fetch };

describe('fetchApi', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('should make GET request and parse JSON response', async () => {
    const mockData = { id: '1', name: 'test' };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockData),
    });

    const result = await fetchApi('/api/test');

    expect(fetch).toHaveBeenCalledWith(
      '/api/test',
      expect.objectContaining({
        headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      }),
    );
    expect(result).toEqual(mockData);
  });

  it('should throw ApiError on non-OK response', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      text: () => Promise.resolve('Not found'),
    });

    await expect(fetchApi('/api/missing')).rejects.toThrow(ApiError);
    await expect(fetchApi('/api/missing')).rejects.toMatchObject({
      status: 404,
      message: 'Not found',
    });
  });

  it('should return undefined for 204 No Content', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
    });

    const result = await fetchApi('/api/delete');
    expect(result).toBeUndefined();
  });

  it('should pass request options through', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({}),
    });

    await fetchApi('/api/test', {
      method: 'POST',
      body: JSON.stringify({ data: 'value' }),
    });

    expect(fetch).toHaveBeenCalledWith(
      '/api/test',
      expect.objectContaining({
        method: 'POST',
        body: '{"data":"value"}',
      }),
    );
  });

  it('should handle network errors', async () => {
    global.fetch = vi.fn().mockRejectedValue(new TypeError('Network error'));

    await expect(fetchApi('/api/test')).rejects.toThrow('Network error');
  });

  it('should use empty string body as error message when text() fails', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      text: () => Promise.reject(new Error('stream consumed')),
    });

    await expect(fetchApi('/api/fail')).rejects.toMatchObject({
      status: 500,
      message: 'HTTP 500',
    });
  });
});

describe('api.companies', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('list() unwraps PageResponse content', async () => {
    const companies = [{ id: '1', name: 'Acme' }];
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        content: companies,
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 100,
      }),
    });

    const result = await api.companies.list();

    expect(fetch).toHaveBeenCalledWith(
      '/api/companies?size=100',
      expect.any(Object),
    );
    expect(result).toEqual(companies);
  });

  it('list() passes status filter in query string', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({
        content: [],
        totalElements: 0,
        totalPages: 0,
        number: 0,
        size: 100,
      }),
    });

    await api.companies.list('ACTIVE');

    expect(fetch).toHaveBeenCalledWith(
      '/api/companies?status=ACTIVE&size=100',
      expect.any(Object),
    );
  });
});

describe('api.pipeline', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('list() calls /api/pipeline', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    });

    await api.pipeline.list();

    expect(fetch).toHaveBeenCalledWith(
      '/api/pipeline',
      expect.any(Object),
    );
  });

  it('list() passes status filter', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    });

    await api.pipeline.list('APPLIED');

    expect(fetch).toHaveBeenCalledWith(
      '/api/pipeline?status=APPLIED',
      expect.any(Object),
    );
  });

  it('apply() calls POST /api/pipeline/{jobId}/apply', async () => {
    const mockApp = { id: 'app-1', status: 'APPLIED' };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(mockApp),
    });

    const result = await api.pipeline.apply('job-123', { notes: 'test' });

    expect(fetch).toHaveBeenCalledWith(
      '/api/pipeline/job-123/apply',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ notes: 'test' }),
      }),
    );
    expect(result).toEqual(mockApp);
  });

  it('apply() sends empty body when no data provided', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({}),
    });

    await api.pipeline.apply('job-456');

    expect(fetch).toHaveBeenCalledWith(
      '/api/pipeline/job-456/apply',
      expect.objectContaining({
        method: 'POST',
        body: '{}',
      }),
    );
  });

  it('updateStatus() calls PUT /api/pipeline/{id}/outcome', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({}),
    });

    await api.pipeline.updateStatus('app-1', 'INTERVIEWING', 'went well');

    expect(fetch).toHaveBeenCalledWith(
      '/api/pipeline/app-1/outcome',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ stage: 'INTERVIEWING', notes: 'went well' }),
      }),
    );
  });

  it('recordOutcome() calls PUT /api/pipeline/{id}/outcome', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({}),
    });

    await api.pipeline.recordOutcome('app-2', { stage: 'PHONE_SCREEN', notes: 'scheduled' });

    expect(fetch).toHaveBeenCalledWith(
      '/api/pipeline/app-2/outcome',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify({ stage: 'PHONE_SCREEN', notes: 'scheduled' }),
      }),
    );
  });
});

describe('api.jobs', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('search() returns PageResponse directly', async () => {
    const pageData = {
      content: [{ id: 'j1', title: 'Dev' }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(pageData),
    });

    const result = await api.jobs.search({ query: 'react', page: 0, size: 20 });

    expect(fetch).toHaveBeenCalledWith(
      '/api/jobs?query=react&page=0&size=20',
      expect.any(Object),
    );
    expect(result).toEqual(pageData);
    expect(result.content).toHaveLength(1);
  });

  it('search() omits empty params from query string', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
    });

    await api.jobs.search({ query: '', location: '', page: 0, size: 20 });

    expect(fetch).toHaveBeenCalledWith(
      '/api/jobs?page=0&size=20',
      expect.any(Object),
    );
  });

  it('getDailyDigest() calls /api/digest', async () => {
    const digest = { date: '2026-06-03', newJobsCount: 5 };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(digest),
    });

    const result = await api.jobs.getDailyDigest();

    expect(fetch).toHaveBeenCalledWith('/api/digest', expect.any(Object));
    expect(result).toEqual(digest);
  });

  it('getRadar() calls /api/jobs/radar', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve([]),
    });

    await api.jobs.getRadar();

    expect(fetch).toHaveBeenCalledWith('/api/jobs/radar', expect.any(Object));
  });
});

describe('api.discovery', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('getEvents() passes pagination params', async () => {
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 }),
    });

    await api.discovery.getEvents(2, 10);

    expect(fetch).toHaveBeenCalledWith(
      '/api/discovery/events?page=2&size=10',
      expect.any(Object),
    );
  });

  it('getStats() calls /api/discovery/stats', async () => {
    const stats = { discoveredCount: 10, resolvedCount: 5, activeCount: 3, pendingCount: 2 };
    global.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: () => Promise.resolve(stats),
    });

    const result = await api.discovery.getStats();

    expect(fetch).toHaveBeenCalledWith('/api/discovery/stats', expect.any(Object));
    expect(result).toEqual(stats);
  });
});
