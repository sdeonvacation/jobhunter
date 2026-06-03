import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  status: z.enum(['INTERESTED', 'APPLIED', 'PHONE_SCREEN', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN']).optional().describe('Filter by pipeline status'),
});

export const getPipelineTool = {
  name: 'get_pipeline',
  description: 'Application pipeline status',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getPipeline(params.status);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
