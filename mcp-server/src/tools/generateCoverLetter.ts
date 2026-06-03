import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({
  job_id: z.string().describe('Job UUID'),
  tone: z.enum(['professional', 'enthusiastic', 'concise']).default('professional').describe('Letter tone'),
  focus: z.string().optional().describe('Specific aspect to emphasize'),
});

export const generateCoverLetterTool = {
  name: 'generate_cover_letter',
  description: 'Generate personalized cover letter matching job requirements to experience',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.generateCoverLetter(params);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
