import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({});

export const getRadarTool = {
  name: 'get_radar',
  description: 'Strategic view: top opportunities, new this week, companies heating up/cooling down',
  inputSchema,
  handler: async (_params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getRadar();
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
