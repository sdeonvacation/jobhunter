import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  since: z.string().optional().describe('Start date for pattern analysis (ISO format, e.g. "2024-01-01"). Defaults to all time.'),
});

export const getApplicationPatternsTool = {
  name: 'get_application_patterns',
  description: 'Analyze application patterns — success rates, common rejection reasons, optimal timing, and trends.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const result = await client.getApplicationPatterns(params.since);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
