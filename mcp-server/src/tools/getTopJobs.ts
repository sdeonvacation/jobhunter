import { z } from 'zod';
import { JobHubClient } from '../client.js';
import { formatJobList } from './getTopJobsToday.js';

const inputSchema = z.object({
  n: z.number().default(10).describe('Number of jobs to return (default: 10)'),
});

export const getTopJobsTool = {
  name: 'get_top_jobs',
  description: 'All active unapplied jobs sorted by match score — best overall opportunities',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const page = await client.searchJobs({ size: params.n, sort: 'matchScore' });
    const text = formatJobList(page.content);
    return { content: [{ type: 'text' as const, text }] };
  },
};
