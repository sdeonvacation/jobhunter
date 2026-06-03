import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  id: z.string().describe('Job UUID'),
});

export const getJobTool = {
  name: 'get_job',
  description: 'Full job detail including description, tech stack, scores, recruiter contact',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getJob(params.id);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
