import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID'),
  emphasis: z.array(z.string()).optional().describe('Skills to emphasize'),
  format: z.enum(['json', 'pdf']).default('json').describe('Output format'),
});

export const tailorResumeTool = {
  name: 'tailor_resume',
  description: 'Generate tailored resume content for a specific job. NEVER invents experience.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.tailorResume(params);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
