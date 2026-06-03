import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({});

export const getDailyDigestTool = {
  name: 'get_daily_digest',
  description: 'Morning briefing: new jobs, top opportunity, registry changes, rate trends',
  inputSchema,
  handler: async (_params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getDailyDigest();
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
