import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  id: z.string().describe('Job UUID'),
});

export const scoreJobTool = {
  name: 'score_job',
  description: 'Match percentage + matched/missing skills + apply/maybe/skip recommendation',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.scoreJob(params.id);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
