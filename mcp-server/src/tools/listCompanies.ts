import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  status: z.enum(['ACTIVE', 'DISCOVERED', 'PAUSED']).optional().describe('Filter by company status'),
  sort: z.enum(['priority', 'name', 'interviewRate']).default('priority').describe('Sort order'),
});

export const listCompaniesTool = {
  name: 'list_companies',
  description: 'Registry with priority scores, interview rates, ATS types',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.listCompanies(params);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
