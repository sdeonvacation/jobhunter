import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({});

export const getDiscoveryStatsTool = {
  name: 'get_discovery_stats',
  description: 'Discovery pipeline: discovered/resolved/active counts',
  inputSchema,
  handler: async (_params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getDiscoveryStats();
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
