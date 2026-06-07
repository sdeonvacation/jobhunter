import { z } from 'zod';
import { JobHunterClient, JobSummary } from '../client.js';

const inputSchema = z.object({
  n: z.number().default(10).describe('Number of jobs to return (default: 10)'),
});

export function formatJobList(jobs: JobSummary[]): string {
  if (jobs.length === 0) return 'No jobs found.';
  return jobs.map((job, i) => {
    const shortId = job.id.slice(0, 8);
    const location = job.location || 'Unknown';
    const match = job.matchScore ?? 0;
    const opp = job.opportunityScore ?? 0;
    const rec = job.recommendation || 'N/A';
    return `${i + 1}. [${shortId}] ${job.title} @ ${job.companyName} | ${location} | Match: ${match} | Opp: ${opp} | ${rec}`;
  }).join('\n');
}

export const getTopJobsTodayTool = {
  name: 'get_top_jobs_today',
  description: "Today's top jobs sorted by match score — quick daily overview",
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const page = await client.getTodayJobs({ size: params.n, sort: 'matchScore' });
    const text = formatJobList(page.content);
    return { content: [{ type: 'text' as const, text }] };
  },
};
