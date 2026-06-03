import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID'),
});

export const getTechStackTool = {
  name: 'get_tech_stack',
  description: 'Structured tech stack for resume tailoring (languages, frameworks, DBs, cloud, tools)',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getTechStack(params.job_id);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
