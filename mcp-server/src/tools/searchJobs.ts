import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  query: z.string().optional().describe('Search query (title, skills, company)'),
  location: z.string().optional().describe('Location filter'),
  min_score: z.number().optional().describe('Minimum OpportunityScore (0-100)'),
  source: z.enum(['GREENHOUSE', 'LEVER', 'ASHBY', 'WORKDAY', 'STEPSTONE']).optional().describe('ATS source filter'),
  limit: z.number().default(20).describe('Max results to return'),
});

export const searchJobsTool = {
  name: 'search_jobs',
  description: 'Search jobs ranked by OpportunityScore with inline score breakdown',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.searchJobs(params);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
