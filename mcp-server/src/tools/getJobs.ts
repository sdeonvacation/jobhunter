import { z } from 'zod';
import { JobHunterClient } from '../client.js';
import { formatJobList } from './getTopJobsToday.js';

const inputSchema = z.object({
  skill: z.string().describe('Skill or keyword to search for'),
  n: z.number().default(10).describe('Number of jobs to return (default: 10)'),
});

export const getJobsTool = {
  name: 'get_jobs',
  description: 'Search jobs by skill/keyword sorted by match score',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const page = await client.searchJobs({ query: params.skill, size: params.n, sort: 'matchScore' });
    const text = formatJobList(page.content);
    return { content: [{ type: 'text' as const, text }] };
  },
};
