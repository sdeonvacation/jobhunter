import { z } from 'zod';
import { JobHubClient } from '../client.js';

const inputSchema = z.object({});

export const getProfileTool = {
  name: 'get_profile',
  description: 'Personal skills, proficiency levels, preferences',
  inputSchema,
  handler: async (_params: z.infer<typeof inputSchema>, client: JobHubClient) => {
    const result = await client.getProfile();
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
