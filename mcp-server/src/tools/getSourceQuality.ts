import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({});

export const getSourceQualityTool = {
  name: 'get_source_quality',
  description: 'Which discovery sources produce interviews',
  inputSchema,
  handler: async (_params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getSourceQuality();
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
