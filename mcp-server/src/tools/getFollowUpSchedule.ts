import { z } from 'zod';
import { JobHunterClient } from '../client.js';

const inputSchema = z.object({
  status: z.enum(['OVERDUE', 'PENDING', 'ALL']).optional().describe('Filter by follow-up status (default: ALL)'),
  limit: z.number().optional().describe('Maximum number of follow-ups to return'),
});

export const getFollowUpScheduleTool = {
  name: 'get_followup_schedule',
  description: 'Get scheduled follow-ups for applications — shows what needs attention and when.',
  inputSchema,
  handler: async (params: z.infer<typeof inputSchema>, client: JobHunterClient) => {
    const result = await client.getFollowUpSchedule(params.status, params.limit);
    return { content: [{ type: 'text' as const, text: JSON.stringify(result, null, 2) }] };
  },
};
