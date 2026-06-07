import { describe, it, expect, vi, beforeEach } from 'vitest';
import { JobHunterClient } from '../client.js';
import { searchJobsTool } from '../tools/searchJobs.js';
import { getJobTool } from '../tools/getJob.js';
import { getTechStackTool } from '../tools/getTechStack.js';
import { scoreJobTool } from '../tools/scoreJob.js';
import { tailorResumeTool } from '../tools/tailorResume.js';
import { generateCoverLetterTool } from '../tools/generateCoverLetter.js';
import { markAppliedTool } from '../tools/markApplied.js';
import { recordOutcomeTool } from '../tools/recordOutcome.js';
import { getPipelineTool } from '../tools/getPipeline.js';
import { getDailyDigestTool } from '../tools/getDailyDigest.js';
import { getRadarTool } from '../tools/getRadar.js';
import { listCompaniesTool } from '../tools/listCompanies.js';
import { getProfileTool } from '../tools/getProfile.js';
import { getDiscoveryStatsTool } from '../tools/getDiscoveryStats.js';
import { getSourceQualityTool } from '../tools/getSourceQuality.js';
import { addCompanyTool } from '../tools/addCompany.js';

describe('Tool definitions', () => {
  const allTools = [
    searchJobsTool,
    getJobTool,
    getTechStackTool,
    scoreJobTool,
    tailorResumeTool,
    generateCoverLetterTool,
    markAppliedTool,
    recordOutcomeTool,
    getPipelineTool,
    getDailyDigestTool,
    getRadarTool,
    listCompaniesTool,
    getProfileTool,
    getDiscoveryStatsTool,
    getSourceQualityTool,
    addCompanyTool,
  ];

  it('exports exactly 16 tools', () => {
    expect(allTools).toHaveLength(16);
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
      'search_jobs', 'get_job', 'get_tech_stack', 'score_job',
      'tailor_resume', 'generate_cover_letter', 'mark_applied', 'record_outcome',
      'get_pipeline', 'get_daily_digest', 'get_radar', 'list_companies',
      'get_profile', 'get_discovery_stats', 'get_source_quality', 'add_company',
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

  it('searchJobs handler returns formatted content', async () => {
    const jobs = [{ id: '1', title: 'Engineer' }];
    mockFetch(jobs);

    const result = await searchJobsTool.handler({ limit: 20 }, client);

    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(jobs);
  });

  it('getJob handler returns formatted content', async () => {
    const job = { id: 'abc', title: 'Dev', description: 'Great role' };
    mockFetch(job);

    const result = await getJobTool.handler({ id: 'abc' }, client);

    expect(result.content[0].type).toBe('text');
    expect(JSON.parse(result.content[0].text)).toEqual(job);
  });

  it('getTechStack handler returns formatted content', async () => {
    const stack = { languages: [{ name: 'Go', required: true }] };
    mockFetch(stack);

    const result = await getTechStackTool.handler({ job_id: 'j1' }, client);

    expect(JSON.parse(result.content[0].text)).toEqual(stack);
  });

  it('scoreJob handler returns formatted content', async () => {
    const score = { matchPercentage: 90, recommendation: 'apply' };
    mockFetch(score);

    const result = await scoreJobTool.handler({ id: 'j1' }, client);

    expect(JSON.parse(result.content[0].text)).toEqual(score);
  });

  it('tailorResume handler calls with correct params', async () => {
    const tailored = { summary: 'Custom summary' };
    mockFetch(tailored);

    const result = await tailorResumeTool.handler(
      { job_id: 'j1', emphasis: ['React'], format: 'json' as const },
      client,
    );

    expect(JSON.parse(result.content[0].text)).toEqual(tailored);
  });

  it('generateCoverLetter handler works', async () => {
    const letter = { content: 'Dear Hiring Manager...' };
    mockFetch(letter);

    const result = await generateCoverLetterTool.handler(
      { job_id: 'j1', tone: 'professional' as const },
      client,
    );

    expect(JSON.parse(result.content[0].text)).toEqual(letter);
  });

  it('markApplied handler works', async () => {
    const applied = { status: 'APPLIED', jobId: 'j1' };
    mockFetch(applied);

    const result = await markAppliedTool.handler({ job_id: 'j1' }, client);

    expect(JSON.parse(result.content[0].text)).toEqual(applied);
  });

  it('recordOutcome handler works', async () => {
    const outcome = { success: true };
    mockFetch(outcome);

    const result = await recordOutcomeTool.handler(
      { application_id: 'a1', outcome: 'OFFER' as const, notes: 'Yay' },
      client,
    );

    expect(JSON.parse(result.content[0].text)).toEqual(outcome);
  });

  it('getPipeline handler works with status', async () => {
    const pipeline = [{ id: 'a1', status: 'APPLIED' }];
    mockFetch(pipeline);

    const result = await getPipelineTool.handler({ status: 'APPLIED' as const }, client);

    expect(JSON.parse(result.content[0].text)).toEqual(pipeline);
  });

  it('getDailyDigest handler works', async () => {
    const digest = { newJobs: 3, topOpportunity: { id: 'x' } };
    mockFetch(digest);

    const result = await getDailyDigestTool.handler({}, client);

    expect(JSON.parse(result.content[0].text)).toEqual(digest);
  });

  it('getRadar handler works', async () => {
    const radar = { topOpportunities: [], newThisWeek: [] };
    mockFetch(radar);

    const result = await getRadarTool.handler({}, client);

    expect(JSON.parse(result.content[0].text)).toEqual(radar);
  });

  it('listCompanies handler works', async () => {
    const companies = [{ id: 'c1', name: 'ACME' }];
    mockFetch(companies);

    const result = await listCompaniesTool.handler({ sort: 'priority' as const }, client);

    expect(JSON.parse(result.content[0].text)).toEqual(companies);
  });

  it('getProfile handler works', async () => {
    const profile = { name: 'John', skills: ['TS'] };
    mockFetch(profile);

    const result = await getProfileTool.handler({}, client);

    expect(JSON.parse(result.content[0].text)).toEqual(profile);
  });

  it('getDiscoveryStats handler works', async () => {
    const stats = { discovered: 50, resolved: 30, active: 20 };
    mockFetch(stats);

    const result = await getDiscoveryStatsTool.handler({}, client);

    expect(JSON.parse(result.content[0].text)).toEqual(stats);
  });

  it('getSourceQuality handler works', async () => {
    const quality = { sources: [{ name: 'GREENHOUSE', interviewRate: 0.3 }] };
    mockFetch(quality);

    const result = await getSourceQualityTool.handler({}, client);

    expect(JSON.parse(result.content[0].text)).toEqual(quality);
  });

  it('addCompany handler works', async () => {
    const company = { id: 'new-1', name: 'NewCo' };
    mockFetch(company);

    const result = await addCompanyTool.handler(
      { careers_url: 'https://newco.com/careers', company_name: 'NewCo' },
      client,
    );

    expect(JSON.parse(result.content[0].text)).toEqual(company);
  });
});

describe('Tool input schema validation', () => {
  it('searchJobs validates min_score as number', () => {
    const result = searchJobsTool.inputSchema.safeParse({ min_score: 'not-a-number' });
    expect(result.success).toBe(false);
  });

  it('searchJobs validates source enum', () => {
    const result = searchJobsTool.inputSchema.safeParse({ source: 'INVALID' });
    expect(result.success).toBe(false);
  });

  it('searchJobs accepts valid params', () => {
    const result = searchJobsTool.inputSchema.safeParse({
      query: 'react',
      source: 'GREENHOUSE',
      min_score: 70,
      limit: 10,
    });
    expect(result.success).toBe(true);
  });

  it('getJob requires id', () => {
    const result = getJobTool.inputSchema.safeParse({});
    expect(result.success).toBe(false);
  });

  it('recordOutcome requires application_id and outcome', () => {
    const noId = recordOutcomeTool.inputSchema.safeParse({ outcome: 'OFFER' });
    expect(noId.success).toBe(false);

    const noOutcome = recordOutcomeTool.inputSchema.safeParse({ application_id: 'a1' });
    expect(noOutcome.success).toBe(false);

    const valid = recordOutcomeTool.inputSchema.safeParse({
      application_id: 'a1',
      outcome: 'OFFER',
    });
    expect(valid.success).toBe(true);
  });

  it('tailorResume validates format enum', () => {
    const invalid = tailorResumeTool.inputSchema.safeParse({ job_id: 'j1', format: 'docx' });
    expect(invalid.success).toBe(false);

    const valid = tailorResumeTool.inputSchema.safeParse({ job_id: 'j1', format: 'pdf' });
    expect(valid.success).toBe(true);
  });

  it('generateCoverLetter validates tone enum', () => {
    const invalid = generateCoverLetterTool.inputSchema.safeParse({ job_id: 'j1', tone: 'angry' });
    expect(invalid.success).toBe(false);
  });

  it('listCompanies validates status and sort enums', () => {
    const invalid = listCompaniesTool.inputSchema.safeParse({ status: 'DELETED' });
    expect(invalid.success).toBe(false);

    const valid = listCompaniesTool.inputSchema.safeParse({ status: 'ACTIVE', sort: 'name' });
    expect(valid.success).toBe(true);
  });
});
