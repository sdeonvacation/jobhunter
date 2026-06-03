import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID'),
  resume_variant: z.string().optional().describe('Which resume version used'),
  notes: z.string().optional().describe('Application notes'),
});

export const markAppliedTool = {
  name: 'mark_applied',
  description: 'Mark a job as applied, moves to pipeline',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.markApplied(params);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
