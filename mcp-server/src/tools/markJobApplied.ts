import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID'),
});

export const markJobAppliedTool = {
  name: 'mark_job_applied',
  description: 'Mark a job as applied',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const fullId = await client.resolveJobId(params.job_id);
    await client.markApplied(fullId);
    return { content: [{ type: 'text' as const, text: `Job ${params.job_id} marked as applied.` }] };
  },
};
